package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Fraud Event Consumer — Critical security notifications.
 * Bypasses quiet hours and rate limiting.
 * Always delivers via PUSH + EMAIL + SMS.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudEventConsumer {

    private final NotificationProcessingService processingService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "fraud.flagged",
            groupId = "notification-service-fraud",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeFraudFlagged(String message,
                                    Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String transactionId = event.path("transactionId").asText();
            String traceId = event.path("traceId").asText("no-trace");

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("amount", event.path("amount").asText());
            ctx.put("currency", event.path("currency").asText("MXN"));
            ctx.put("blockedAmount", event.path("amount").asText());
            ctx.put("decision", event.path("decision").asText());
            ctx.put("riskScore", event.path("riskScore").asText());
            ctx.put("transactionId", transactionId);
            // Do NOT include: raw fraud signals, triggering factors
            // (would reveal fraud model internals)

            processingService.process(
                    NotificationEventType.FRAUD_ALERT,
                    userId, transactionId, ctx, traceId,
                    "fraud.flagged");

            ack.acknowledge();
            log.info("Fraud alert notification processed: userId={}",
                    userId);

        } catch (Exception e) {
            log.error("Failed to process fraud.flagged: {}",
                    e.getMessage(), e);
        }
    }
}