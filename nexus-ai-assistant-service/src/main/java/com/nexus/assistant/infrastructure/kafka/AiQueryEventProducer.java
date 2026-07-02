package com.nexus.assistant.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public void publishQueryLogged(String userId, String sessionId,
                                   String message, long durationMs) {
        try {
            Map<String, Object> event = Map.of(
                    "eventType", "ai.query.logged",
                    "userId", userId,
                    "sessionId", sessionId,
                    "queryLength", message != null ? message.length() : 0,
                    "processingDurationMs", durationMs,
                    "loggedAt", Instant.now().toString()
            );

            kafkaTemplate.send("ai.query.logged", userId,
                    objectMapper.writeValueAsString(event));

        } catch (Exception e) {
            log.warn("Failed to publish ai.query.logged: {}", e.getMessage());
            // Non-fatal — analytics loss is acceptable
        }
    }
}