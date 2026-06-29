package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityEventsConsumer {

    private final NotificationProcessingService processingService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "identity.events",
            groupId = "notification-service-identity-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIdentityEvent(String message, Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText();
            String userId = event.path("userId").asText();
            String traceId = event.path("traceId").asText();

            switch (eventType) {
                case "PasswordResetRequested" -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("email", event.path("email").asText());
                    ctx.put("resetToken", event.path("resetToken").asText());
                    ctx.put("expiresAt", event.path("expiresAt").asText());
                    processingService.process(
                            NotificationEventType.PASSWORD_RESET_REQUESTED,
                            userId, userId, ctx, traceId, "identity.events");
                }
                case "PasswordResetCompleted" -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("completedAt", event.path("completedAt").asText());
                    processingService.process(
                            NotificationEventType.PASSWORD_RESET_COMPLETED,
                            userId, userId, ctx, traceId, "identity.events");
                }
                case "PasswordChanged" -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("changedAt", event.path("changedAt").asText());
                    processingService.process(
                            NotificationEventType.PASSWORD_CHANGED,
                            userId, userId, ctx, traceId, "identity.events");
                }
                default -> log.debug("identity.events: unhandled eventType={}", eventType);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("identity.events processing failed: {}", e.getMessage(), e);
        }
    }
}
