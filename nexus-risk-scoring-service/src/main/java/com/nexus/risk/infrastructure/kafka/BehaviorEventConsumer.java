package com.nexus.risk.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.agent.model.RiskScoringAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final Executor asyncExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    @KafkaListener(
            topics = "user.behavior.aggregated",
            groupId = "risk-scoring-behavior-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeBehaviorAggregated(String message,
                                          Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String trigger = event.path("trigger").asText();

            if (isMeaningfulTrigger(trigger)) {
                log.info("Event-triggered recomputation: " +
                        "userId={} trigger={}", userId, trigger);

                // Async — don't block Kafka consumer thread
                CompletableFuture.runAsync(() -> {
                    try {
                        riskScoringAgent.computeRiskProfile(
                                userId, "EVENT_TRIGGERED");
                    } catch (Exception e) {
                        log.error("Event-triggered scoring failed: " +
                                "userId={} {}", userId, e.getMessage());
                    }
                }, asyncExecutor);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process behavior event: {}",
                    e.getMessage(), e);
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