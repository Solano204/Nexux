package com.nexus.analytics.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.domain.model.SpendingAnomaly;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Anomaly Event Producer — publishes anomalies to Kafka.
 *
 * Topic: analytics.anomalies.detected
 * Consumed by: Notification Service (anomaly alerts),
 *              Audit Service (compliance records).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishAnomaly(SpendingAnomaly anomaly) {
        try {
            // ✅ Fix: Use Map.ofEntries() for more than 10 entries
            Map<String, Object> event = Map.ofEntries(
                    Map.entry("eventType", "analytics.anomaly.detected"),
                    Map.entry("anomalyId", anomaly.getAnomalyId()),
                    Map.entry("userId", anomaly.getUserId()),
                    Map.entry("type", anomaly.getType().name()),
                    Map.entry("category", anomaly.getCategory()),
                    Map.entry("severity", anomaly.getSeverity()),
                    Map.entry("percentageChange", anomaly.getPercentageChange()),
                    Map.entry("absoluteChange", anomaly.getAbsoluteChange().toPlainString()),
                    Map.entry("currency", anomaly.getCurrency()),
                    Map.entry("topContributingMerchants", anomaly.getTopContributingMerchants()),
                    Map.entry("detectedAt", Instant.now().toString())
            );

            kafkaTemplate.send("analytics.anomalies.detected",
                    anomaly.getUserId(),
                    objectMapper.writeValueAsString(event));

            log.info("Anomaly published: userId={} category={} severity={}",
                    anomaly.getUserId(), anomaly.getCategory(),
                    anomaly.getSeverity());

        } catch (Exception e) {
            log.error("Failed to publish anomaly event: {}", e.getMessage());
        }
    }
}