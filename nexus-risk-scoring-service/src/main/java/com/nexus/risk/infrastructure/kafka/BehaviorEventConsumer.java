package com.nexus.risk.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.agent.model.RiskScoringAgent;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Behavior Event Consumer — event-triggered recomputation.
 *
 * Consumes: user.behavior.aggregated
 * Only triggers recomputation for HIGH-SIGNAL events:
 * - Large transaction detected
 * - Fraud flag on account
 * - KYC re-verification complete
 * - Account dormancy ended
 * - Income pattern break
 * - Counterparty flagged
 *
 * Throttle: skip if profile was computed within last 6 hours.
 * Non-blocking: computation runs async via Virtual Thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventConsumer {

    private final RiskScoringAgent riskScoringAgent;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    private final Executor asyncExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    @KafkaListener(
            topics = "user.behavior.aggregated",
            groupId = "risk-scoring-behavior-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeBehaviorAggregated(ConsumerRecord<String, String> record,
                                          Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "risk-scoring-behavior-events", "user.behavior.aggregated receive");
        try (Tracer.SpanInScope ignoredScope = tracer.withSpan(span)) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String trigger = event.path("trigger").asText();

            if (isMeaningfulTrigger(trigger)) {
                log.info("Event-triggered recomputation: " +
                        "userId={} trigger={}", userId, trigger);

                // The computation runs on a separate thread after this
                // method returns, so the "receive" span above (which ends
                // in this method's finally block, right after dispatch)
                // can't cover it. Span objects are plain trace-context
                // data - safe to hand to another thread - so a dedicated
                // child span is built here, explicitly parented to the
                // receive span's context via setParent(), with its own
                // scope opened and closed entirely inside the async
                // lambda (never handing a Scope itself across threads).
                Span computeSpan = tracer.spanBuilder()
                        .setParent(span.context())
                        .name("risk.compute.event-triggered")
                        .start();

                // Async — don't block Kafka consumer thread
                CompletableFuture.runAsync(() -> {
                    try (Tracer.SpanInScope computeScope = tracer.withSpan(computeSpan)) {
                        riskScoringAgent.computeRiskProfile(
                                userId, "EVENT_TRIGGERED");
                    } catch (Exception e) {
                        computeSpan.error(e);
                        log.error("Event-triggered scoring failed: " +
                                "userId={} {}", userId, e.getMessage());
                    } finally {
                        computeSpan.end();
                    }
                }, asyncExecutor);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process behavior event: {}",
                    e.getMessage(), e);
        }
        } finally {
            span.end();
        }
    }

    private boolean isMeaningfulTrigger(String trigger) {
        return switch (trigger) {
            case "LARGE_TRANSACTION_DETECTED",
                 "FRAUD_FLAG_ON_ACCOUNT",
                 "KYC_REVERIFICATION_COMPLETED",
                 "ACCOUNT_DORMANCY_ENDED",
                 "INCOME_PATTERN_BREAK_DETECTED",
                 "COUNTERPARTY_FLAGGED",
                 "TRANSACTION_COMPLETED" -> true;
            default -> false;
        };
    }
}