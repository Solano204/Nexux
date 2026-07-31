package com.nexus.assistant.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * AI Query Event Producer — publishes ai.query.logged to Kafka.
 *
 * Every chat interaction produces an analytics event consumed by
 * nexus-analytics-service for: daily active AI users, query categories,
 * token cost per user, tool usage patterns, agent vs simple ratio.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiQueryEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final ObservationRegistry observationRegistry;

    public void publishQueryLogged(String userId, String sessionId,
                                   String message, long durationMs) {
        Observation obs = Observation.createNotStarted(
                        "kafka.publish", observationRegistry)
                .lowCardinalityKeyValue("topic", "ai.query.logged")
                .highCardinalityKeyValue("kafka.key", userId)
                .start();
        try {
            Map<String, Object> event = Map.of(
                    "eventType", "ai.query.logged",
                    "userId", userId,
                    "sessionId", sessionId,
                    "queryLength", message != null ? message.length() : 0,
                    "processingDurationMs", durationMs,
                    "loggedAt", Instant.now().toString()
            );

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    "ai.query.logged", userId, objectMapper.writeValueAsString(event));
            KafkaTracePropagation.injectTraceHeaders(tracer, propagator, record);
            kafkaTemplate.send(record);

        } catch (Exception e) {
            obs.error(e);
            log.warn("Failed to publish ai.query.logged: {}", e.getMessage());
            // Non-fatal — analytics loss is acceptable
        } finally {
            obs.stop();
        }
    }
}