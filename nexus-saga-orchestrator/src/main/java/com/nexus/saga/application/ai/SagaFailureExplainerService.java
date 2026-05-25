package com.nexus.saga.application.ai;

import com.nexus.saga.domain.model.SagaFailureContext;
import com.nexus.saga.domain.model.SagaFailureExplanation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;

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

    public SagaFailureExplainerService(
            @Qualifier("sagaFailureExplainerClient")
            ChatClient explainerClient,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.explainerClient = explainerClient;
        this.observationRegistry = observationRegistry;

        this.aiExplanationCounter =
                Counter.builder("saga.ai.explanation.total")
                        .tag("method", "AI").register(meterRegistry);
        this.fallbackExplanationCounter =
                Counter.builder("saga.ai.explanation.total")
                        .tag("method", "FALLBACK").register(meterRegistry);
    }

    /**
     * Generates a user-facing failure explanation.
     * Always succeeds — uses fallback on any AI failure.
     */
    public SagaFailureExplanation explain(SagaFailureContext ctx) {

        Observation obs = Observation.createNotStarted(
                "saga.ai.explain", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            String prompt = buildPrompt(ctx);

            SagaFailureExplanation explanation =
                    explainerClient.prompt()
                            .user(prompt)
                            .call()
                            .entity(SagaFailureExplanation.class);

            if (explanation == null) {
                throw new RuntimeException(
                        "AI returned null explanation");
            }

            aiExplanationCounter.increment();
            obs.event(Observation.Event.of("ai.explain.success"));

            log.info("AI failure explanation: type={} lang={} canRetry={}",
                    ctx.failureType(), ctx.language(),
                    explanation.canRetry());

            return explanation;

        } catch (Exception e) {
            obs.error(e);
            fallbackExplanationCounter.increment();
            log.warn("AI explanation failed, using fallback: {}",
                    e.getMessage());
            return SagaFailureExplanation.fallback(
                    ctx.failureType().name(),
                    ctx.fundsAreReleased(),
                    ctx.canRetry(),
                    ctx.language());
        } finally {
            obs.stop();
        }
    }

    private String buildPrompt(SagaFailureContext ctx) {
        boolean es = "es".equals(ctx.language());

        return switch (ctx.failureType()) {

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
                    ctx.amount(), ctx.currency(), ctx.targetName(),
                    ctx.fundsWereReserved(), ctx.fundsAreReleased(),
                    ctx.language());

            case INSUFFICIENT_FUNDS -> """
                Explain a failed transfer due to insufficient funds.
                Attempted amount: %s %s
                Can retry: yes, after adding funds
                Language: %s

                Be direct but not harsh.
                Tell them to check their balance and try again.
                """.formatted(ctx.amount(), ctx.currency(),
                    ctx.language());

            case KYC_REJECTED -> """
                Explain a failed identity verification to a new user.
                Can retry: %s
                Retry guidance: submit a clear photo of a valid,
                non-expired government-issued ID
                Language: %s

                Be empathetic — this is a new user.
                This is not suspicious, just a quality issue.
                """.formatted(ctx.canRetry(), ctx.language());

            case SAGA_TIMEOUT -> """
                Explain that a financial operation timed out.
                Amount: %s %s
                Funds released: %s
                Can retry: yes
                Language: %s

                Be reassuring — no funds were permanently lost.
                Technical delays happen, user is not at fault.
                """.formatted(ctx.amount(), ctx.currency(),
                    ctx.fundsAreReleased(), ctx.language());

            case COMPENSATION_FAILED -> """
                Explain that an operation failed AND our team
                is manually resolving the situation.
                Amount: %s %s
                Language: %s

                Be honest: there is an issue being resolved manually.
                Give support reference number.
                Do NOT explain technical details.
                """.formatted(ctx.amount(), ctx.currency(),
                    ctx.language());
        };
    }
}