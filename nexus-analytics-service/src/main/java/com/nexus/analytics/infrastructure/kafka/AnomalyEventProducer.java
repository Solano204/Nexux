package com.nexus.analytics.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.domain.model.SpendingAnomaly;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
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
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObservationRegistry observationRegistry;

    public void publishAnomaly(SpendingAnomaly anomaly) {
        Observation obs = Observation.createNotStarted(
                        "kafka.publish", observationRegistry)
                .lowCardinalityKeyValue("topic", "analytics.anomalies.detected")
                .highCardinalityKeyValue("kafka.key", anomaly.getUserId())
                .start();
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

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    "analytics.anomalies.detected", anomaly.getUserId(),
                    objectMapper.writeValueAsString(event));
            KafkaTracePropagation.injectTraceHeaders(tracer, propagator, record);
            kafkaTemplate.send(record);

            log.info("Anomaly published: userId={} category={} severity={}",
                    anomaly.getUserId(), anomaly.getCategory(),
                    anomaly.getSeverity());

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to publish anomaly event: {}", e.getMessage());
        } finally {
            obs.stop();
        }
    }
}