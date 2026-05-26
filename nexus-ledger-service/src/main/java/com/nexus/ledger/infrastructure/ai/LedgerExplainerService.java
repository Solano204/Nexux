package com.nexus.ledger.infrastructure.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import java.util.UUID;

/**
 * Ledger Explainer Service — AI-powered financial transaction explanation.
 *
 * Implements the full Advanced RAG pipeline (Section 10) + streaming (Section 3).
 *
 * Flow:
 * 1. User asks "explain my last 5 transactions"
 * 2. RAG retrieves relevant financial literacy context from pgvector
 * 3. Tool calls fetch live ledger data (recent entries, monthly summary)
 * 4. LLM synthesizes explanation with context + data
 * 5. Response streams token by token via SSE
 * 6. Session memory stores exchange for follow-up questions
 */
@Slf4j
@Service
public class LedgerExplainerService {

    private final ChatClient explainerClient;
    private final ObservationRegistry observationRegistry;

    private final Counter sessionsCounter;
    private final Timer explainerTimer;

    public LedgerExplainerService(
            @Qualifier("ledgerExplainerClient")
            ChatClient explainerClient,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.explainerClient = explainerClient;
        this.observationRegistry = observationRegistry;

        this.sessionsCounter =
                Counter.builder("ledger.explainer.sessions.total")
                        .register(meterRegistry);

        this.explainerTimer =
                Timer.builder("ledger.explainer.duration.seconds")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);
    }

    /**
     * Streaming ledger explanation (Section 3: SSE streaming).
     *
     * Returns Flux<String> for Server-Sent Events streaming.
     * Tokens are returned as they are generated — users see the
     * response building in real time.
     */
    public Flux<String> explainStreaming(UUID accountId,
                                         String userMessage,
                                         String sessionId) {

        Observation obs = Observation.createNotStarted(
                        "ledger.explainer", observationRegistry)
                .start();

        sessionsCounter.increment();

        Timer.Sample sample = Timer.start();

        String enrichedMessage = userMessage +
                "\n[Account ID for tool calls: " + accountId + "]";

        return explainerClient.prompt()
                .user(enrichedMessage)
                .advisors(advisorSpec -> advisorSpec
                        .param("chat_memory_conversation_id", sessionId))
                .stream()
                .content()
                .doOnComplete(() -> {
                    sample.stop(explainerTimer);
                    obs.event(Observation.Event.of("explainer.stream.complete"));
                    obs.stop();
                    log.info("Ledger explain complete: accountId={} sessionId={}",
                            accountId, sessionId);
                })
                .doOnError(e -> {
                    obs.error(e);
                    obs.stop();
                    log.error("Ledger explain error: {}", e.getMessage(), e);
                });
    }
}