package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.notification.infrastructure.mongodb.PreferencesRepository;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final SnsClient snsClient;
    private final PreferencesRepository preferencesRepository;

    @Value("${nexus.aws.notification-dispatch-topic-arn:}")
    private String notificationDispatchTopicArn;

    @KafkaListener(
            topics = "fraud.flagged",
            groupId = "notification-service-fraud",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeFraudFlagged(String message, Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String transactionId = event.path("transactionId").asText();
            String traceId = event.path("traceId").asText("no-trace");
            String amount = event.path("amount").asText("0");
            String currency = event.path("currency").asText("MXN");

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("amount", amount);
            ctx.put("currency", currency);
            ctx.put("blockedAmount", amount);
            ctx.put("decision", event.path("decision").asText());
            ctx.put("riskScore", event.path("riskScore").asText());
            ctx.put("transactionId", transactionId);

            boolean sent = processingService.process(
                    NotificationEventType.FRAUD_ALERT,
                    userId, transactionId, ctx, traceId, "fraud.flagged");

            if (!notificationDispatchTopicArn.isBlank()) {
                dispatchFraudAlertViaSns(userId, transactionId, amount, currency);
            }

            ack.acknowledge();
            log.info("Fraud alert notification processed: userId={} snsDispatched={}",
                    userId, !notificationDispatchTopicArn.isBlank());

        } catch (Exception e) {
            log.error("Failed to process fraud.flagged: {}", e.getMessage(), e);
        }
    }

    private void dispatchFraudAlertViaSns(String userId, String transactionId,
                                          String amount, String currency) {
        try {
            UserNotificationPreferences prefs =
                    preferencesRepository.findByUserId(userId).orElse(null);

            String email = prefs != null && prefs.getEmailConfig() != null
                    ? prefs.getEmailConfig().address() : "";
            String phone = prefs != null && prefs.getSmsConfig() != null
                    ? prefs.getSmsConfig().phoneNumber() : "";

            String alertBody = "Se detectó actividad sospechosa en tu cuenta. " +
                    "Monto bloqueado: " + amount + " " + currency + ". " +
                    "Si no reconoces esta operación, llama al 800-NEXUS-01 de inmediato.";

            for (String channel : new String[]{"EMAIL", "SMS", "PUSH"}) {
                String target = switch (channel) {
                    case "EMAIL" -> email;
                    case "SMS" -> phone;
                    case "PUSH" -> prefs != null && prefs.getPushConfig() != null
                            && prefs.getPushConfig().deviceArns() != null
                            && !prefs.getPushConfig().deviceArns().isEmpty()
                            ? prefs.getPushConfig().deviceArns().get(0) : "";
                    default -> "";
                };
                if (target == null || target.isBlank()) continue;

                ObjectNode dispatch = objectMapper.createObjectNode()
                        .put("dispatchId", UUID.randomUUID().toString())
                        .put("notificationId", transactionId)
                        .put("userId", userId)
                        .put("channel", channel)
                        .put("priority", "HIGH")
                        .put("eventType", "FRAUD_ALERT")
                        .put("toEmail", "EMAIL".equals(channel) ? target : "")
                        .put("subject", "Alerta de seguridad — Actividad sospechosa")
                        .put("textBody", alertBody)
                        .put("htmlBody", "<p><strong>Alerta de seguridad</strong></p><p>" + alertBody + "</p>")
                        .put("toPhoneNumber", "SMS".equals(channel) ? target : "")
                        .put("smsBody", alertBody)
                        .put("pushTitle", "Alerta de seguridad")
                        .put("pushBody", alertBody)
                        .put("deviceEndpointArn", "PUSH".equals(channel) ? target : "")
                        .put("language", "es");

                snsClient.publish(PublishRequest.builder()
                        .topicArn(notificationDispatchTopicArn)
                        .message(objectMapper.writeValueAsString(dispatch))
                        .messageAttributes(Map.of(
                                "channel", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(channel)
                                        .build(),
                                "eventType", MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue("FRAUD_ALERT")
                                        .build()))
                        .build());

                log.info("Fraud alert SNS dispatch: userId={} channel={}", userId, channel);
            }

        } catch (Exception e) {
            log.error("Failed to dispatch fraud alert via SNS: userId={} error={}", userId, e.getMessage());
        }
    }
}