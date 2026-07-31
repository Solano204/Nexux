package com.nexus.saga.application.ai;

import com.nexus.saga.domain.model.SagaFailureContext;
import com.nexus.saga.domain.model.SagaFailureExplanation;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Saga Failure Explainer Service — Section 3 structured output.
 *
 * Called when any SAGA fails — either compensation triggered or
 * terminal failure reached.
 *
 * Builds a context-aware prompt based on failure type, then calls
 * gpt-4o-mini with entity(SagaFailureExplanation.class).
 *
 * Always produces a SagaFailureExplanation — either AI or fallback.
 * The Notification Service consumes this to inform the user.
 */
@Slf4j
@Service
public class SagaFailureExplainerService {

    private final ChatClient explainerClient;
    private final ObservationRegistry observationRegistry;

    private final Counter aiExplanationCounter;
    private final Counter fallbackExplanationCounter;
    private final Counter timeoutExplanationCounter;

    // This call used to run inline on the Kafka listener thread (saga.replies,
    // concurrency=3) with no timeout — a slow/hanging gpt-4o-mini call held a
    // listener thread (and the caller's open @Transactional) for as long as
    // the HTTP client allowed, starving the other 2 threads for unrelated
    // sagas. explain() is enrichment content for an already-terminal saga
    // failure, not a gate anything downstream waits on, so it's dispatched
    // here and bounded by EXPLAIN_TIMEOUT instead of blocking indefinitely.
    // Wrapped with ContextExecutorService: a raw virtual-thread executor doesn't
    // inherit the calling thread's ThreadLocal Brave trace context, so the
    // "saga.ai.explain" span opened below and the gpt-4o-mini call span inside
    // callAi() would land in two disconnected traces instead of parent/child.
    private static final long EXPLAIN_TIMEOUT_SECONDS = 5;
    private final ExecutorService explainerExecutor =
            ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());

    public SagaFailureExplainerService(
            @Qualifier("sagaFailureExplainerClient")
            ChatClient explainerClient,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.explainerClient       = explainerClient;
        this.observationRegistry   = observationRegistry;

        this.aiExplanationCounter =
                Counter.builder("saga.ai.explanation.total")
                        .tag("method", "AI").register(meterRegistry);
        this.fallbackExplanationCounter =
                Counter.builder("saga.ai.explanation.total")
                        .tag("method", "FALLBACK").register(meterRegistry);
        this.timeoutExplanationCounter =
                Counter.builder("saga.ai.explanation.total")
                        .tag("method", "TIMEOUT").register(meterRegistry);
    }

    @PreDestroy
    public void shutdown() {
        explainerExecutor.shutdownNow();
    }

    /**
     * Generates a user-facing failure explanation.
     * Always succeeds — uses fallback on any AI failure or on timeout.
     */
    public SagaFailureExplanation explain(SagaFailureContext ctx) {

        Observation obs = Observation.createNotStarted(
                "saga.ai.explain", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            Future<SagaFailureExplanation> future =
                    explainerExecutor.submit(() -> callAi(ctx));

            SagaFailureExplanation explanation =
                    future.get(EXPLAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            aiExplanationCounter.increment();
            obs.event(Observation.Event.of("ai.explain.success"));

            // ✅ FIX: SagaFailureContext uses @Getter — call getX(), not x()
            log.info("AI failure explanation: type={} lang={} canRetry={}",
                    ctx.getFailureType(), ctx.getLanguage(),
                    explanation.canRetry());

            return explanation;

        } catch (TimeoutException e) {
            obs.error(e);
            timeoutExplanationCounter.increment();
            log.warn("AI explanation timed out after {}s, using fallback: type={}",
                    EXPLAIN_TIMEOUT_SECONDS, ctx.getFailureType());
            return fallbackFor(ctx);

        } catch (Exception e) {
            obs.error(e);
            fallbackExplanationCounter.increment();
            log.warn("AI explanation failed, using fallback: {}",
                    e.getMessage());
            return fallbackFor(ctx);
        } finally {
            obs.stop();
        }
    }

    private SagaFailureExplanation callAi(SagaFailureContext ctx) {
        String prompt = buildPrompt(ctx);

        SagaFailureExplanation explanation =
                explainerClient.prompt()
                        .user(prompt)
                        .call()
                        .entity(SagaFailureExplanation.class);

        if (explanation == null) {
            throw new RuntimeException("AI returned null explanation");
        }
        return explanation;
    }

    // ✅ FIX: getX() throughout — SagaFailureContext is a @Getter class, not a record
    private SagaFailureExplanation fallbackFor(SagaFailureContext ctx) {
        return SagaFailureExplanation.fallback(
                ctx.getFailureType().name(),
                ctx.isFundsAreReleased(),
                ctx.isCanRetry(),
                ctx.getLanguage());
    }

    private String buildPrompt(SagaFailureContext ctx) {
        // ✅ FIX: getFailureType() not failureType() — @Getter class
        return switch (ctx.getFailureType()) {

            case FRAUD_REJECTED -> """
                    Explain a blocked transfer to the user.
                    Amount: %s %s to %s
                    Funds were reserved: %s
                    Funds have been released: %s
                    Can retry: yes
                    Language: %s

                    Important context:
                    - A security check blocked this transfer
                    - Do NOT explain why specifically
                    - Do NOT use words like "fraud" or "suspicious"
                    - DO say funds are safe if they were released
                    - Suggest retrying or calling support
                    """.formatted(
                    ctx.getAmount(), ctx.getCurrency(), ctx.getTargetName(),
                    ctx.isFundsWereReserved(), ctx.isFundsAreReleased(),
                    ctx.getLanguage());

            case INSUFFICIENT_FUNDS -> """
                    Explain a failed transfer due to insufficient funds.
                    Attempted amount: %s %s
                    Can retry: yes, after adding funds
                    Language: %s

                    Be direct but not harsh.
                    Tell them to check their balance and try again.
                    """.formatted(
                    ctx.getAmount(), ctx.getCurrency(), ctx.getLanguage());

            case KYC_REJECTED -> """
                    Explain a failed identity verification to a new user.
                    Can retry: %s
                    Retry guidance: submit a clear photo of a valid,
                    non-expired government-issued ID
                    Language: %s

                    Be empathetic — this is a new user.
                    This is not suspicious, just a quality issue.
                    """.formatted(ctx.isCanRetry(), ctx.getLanguage());

            case SAGA_TIMEOUT -> """
                    Explain that a financial operation timed out.
                    Amount: %s %s
                    Funds released: %s
                    Can retry: yes
                    Language: %s

                    Be reassuring — no funds were permanently lost.
                    Technical delays happen, user is not at fault.
                    """.formatted(
                    ctx.getAmount(), ctx.getCurrency(),
                    ctx.isFundsAreReleased(), ctx.getLanguage());

            case COMPENSATION_FAILED -> """
                    Explain that an operation failed AND our team
                    is manually resolving the situation.
                    Amount: %s %s
                    Language: %s

                    Be honest: there is an issue being resolved manually.
                    Give support reference number.
                    Do NOT explain technical details.
                    """.formatted(
                    ctx.getAmount(), ctx.getCurrency(), ctx.getLanguage());
        };
    }
}