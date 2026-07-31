package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.notification.infrastructure.mongodb.PreferencesRepository;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityEventsConsumer {

    private final NotificationProcessingService processingService;
    private final ObjectMapper objectMapper;
    private final SnsClient snsClient;
    private final PreferencesRepository preferencesRepository;
    private final Tracer tracer;
    private final Propagator propagator;

    @Value("${nexus.aws.notification-dispatch-topic-arn:}")
    private String notificationDispatchTopicArn;

    @KafkaListener(
            topics = "identity.events",
            groupId = "notification-service-identity-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIdentityEvent(ConsumerRecord<String, String> record,
                                     Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "notification-service-identity-events", "identity.events receive");
        try (Tracer.SpanInScope ignoredScope = tracer.withSpan(span)) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText();
            String userId = event.path("userId").asText();
            String traceId = event.path("traceId").asText();

            switch (eventType) {
                case "PasswordResetRequested" -> {
                    String email = event.path("email").asText();
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("email", email);
                    ctx.put("resetToken", event.path("resetToken").asText());
                    ctx.put("expiresAt", event.path("expiresAt").asText());
                    boolean sent = processingService.process(
                            NotificationEventType.PASSWORD_RESET_REQUESTED,
                            userId, userId, ctx, traceId, "identity.events");
                    if (sent && !notificationDispatchTopicArn.isBlank()) {
                        if (email.isBlank()) {
                            log.warn("Skipping security alert SNS publish — " +
                                    "no email on event: userId={} eventType=PasswordResetRequested", userId);
                        } else {
                            publishSecurityAlert(userId, NotificationEventType.PASSWORD_RESET_REQUESTED,
                                    email, "Solicitud de restablecimiento de contraseña",
                                    "Recibimos una solicitud para restablecer tu contraseña. Si no fuiste tú, ignora este mensaje.");
                        }
                    }
                }
                case "PasswordResetCompleted" -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("completedAt", event.path("completedAt").asText());
                    boolean sent = processingService.process(
                            NotificationEventType.PASSWORD_RESET_COMPLETED,
                            userId, userId, ctx, traceId, "identity.events");
                    if (sent && !notificationDispatchTopicArn.isBlank()) {
                        String email = resolveEmail(userId);
                        if (email.isBlank()) {
                            log.warn("Skipping security alert SNS publish — " +
                                    "could not resolve email: userId={} eventType=PasswordResetCompleted", userId);
                        } else {
                            publishSecurityAlert(userId, NotificationEventType.PASSWORD_RESET_COMPLETED,
                                    email, "Tu contraseña fue restablecida",
                                    "Tu contraseña fue restablecida exitosamente. Si no fuiste tú, contacta a soporte inmediatamente.");
                        }
                    }
                }
                case "PasswordChanged" -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("changedAt", event.path("changedAt").asText());
                    boolean sent = processingService.process(
                            NotificationEventType.PASSWORD_CHANGED,
                            userId, userId, ctx, traceId, "identity.events");
                    if (sent && !notificationDispatchTopicArn.isBlank()) {
                        String email = resolveEmail(userId);
                        if (email.isBlank()) {
                            log.warn("Skipping security alert SNS publish — " +
                                    "could not resolve email: userId={} eventType=PasswordChanged", userId);
                        } else {
                            publishSecurityAlert(userId, NotificationEventType.PASSWORD_CHANGED,
                                    email, "Tu contraseña fue cambiada",
                                    "Tu contraseña fue cambiada exitosamente. Si no fuiste tú, contacta a soporte al 800-NEXUS-01 de inmediato.");
                        }
                    }
                }
                default -> log.debug("identity.events: unhandled eventType={}", eventType);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("identity.events processing failed: {}", e.getMessage(), e);
        }
        } finally {
            span.end();
        }
    }

    private void publishSecurityAlert(String userId, NotificationEventType eventType,
                                      String email, String subject, String body) {
        try {
            ObjectNode dispatch = objectMapper.createObjectNode()
                    .put("dispatchId", UUID.randomUUID().toString())
                    .put("notificationId", UUID.randomUUID().toString())
                    .put("userId", userId)
                    .put("channel", "EMAIL")
                    .put("priority", "HIGH")
                    .put("eventType", eventType.name())
                    .put("toEmail", email != null ? email : "")
                    .put("subject", subject)
                    .put("textBody", body)
                    .put("htmlBody", "<p>" + body + "</p>")
                    .put("language", "es");

            snsClient.publish(PublishRequest.builder()
                    .topicArn(notificationDispatchTopicArn)
                    .message(objectMapper.writeValueAsString(dispatch))
                    .messageAttributes(Map.of(
                            "channel", MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue("EMAIL")
                                    .build(),
                            "eventType", MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(eventType.name())
                                    .build()))
                    .build());

            log.info("Security alert dispatched to SNS: userId={} event={}", userId, eventType);

        } catch (Exception e) {
            log.error("Failed to publish security alert to SNS: userId={} error={}", userId, e.getMessage());
        }
    }

    private String resolveEmail(String userId) {
        try {
            return preferencesRepository.findByUserId(userId)
                    .map(UserNotificationPreferences::getEmailConfig)
                    .map(c -> c != null ? c.address() : null)
                    .orElse("");
        } catch (Exception e) {
            log.warn("Could not resolve email for userId={}: {}", userId, e.getMessage());
            return "";
        }
    }
}
