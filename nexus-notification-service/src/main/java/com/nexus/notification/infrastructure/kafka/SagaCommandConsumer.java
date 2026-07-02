package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Saga Command Consumer — SAGA participant for notification delivery.
 *
 * Subscribes to: saga.commands
 * Processes: SendWelcomeNotificationCommand, SendTransactionNotificationCommand
 * Replies to: saga.replies via KafkaTemplate (MongoDB Change Streams
 *             used for outbox in production; direct Kafka reply for simplicity here)
 *
 * Unlike event-driven consumers, SAGA notifications are synchronously
 * coordinated — the orchestrator waits for NotificationSentReply.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandConsumer {

    private final NotificationProcessingService processingService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final SnsClient snsClient;

    @Value("${nexus.aws.notification-dispatch-topic-arn:}")
    private String notificationDispatchTopicArn;

    @KafkaListener(
            topics = "saga.commands",
            groupId = "notification-service-saga",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSagaCommand(String message, Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "saga.commands")
                .lowCardinalityKeyValue("consumerGroup",
                        "notification-service-saga")
                .start();

        try {
            JsonNode command = objectMapper.readTree(message);
            String commandType = command.path("commandType").asText();
            String targetService = command.path("targetService").asText();

            // Only process commands for notification service
            if (!targetService.equals("nexus-notification-service")) {
                ack.acknowledge();
                return;
            }

            String sagaId = command.path("sagaId").asText();
            String traceId = command.path("traceId").asText("no-trace");
            JsonNode payload = command.path("payload");

            log.info("SAGA command received: type={} sagaId={}",
                    commandType, sagaId);

            String userId = payload.path("userId").asText();
            String eventId = payload.path("eventId")
                    .asText(sagaId);

            // Determine event type from command
            NotificationEventType eventType = switch (commandType) {
                case "SendWelcomeNotificationCommand" ->
                        NotificationEventType.WELCOME;
                case "SendTransactionNotificationCommand" ->
                        NotificationEventType.TRANSACTION_COMPLETED;
                case "SendFraudAlertCommand" ->
                        NotificationEventType.FRAUD_ALERT;
                default -> NotificationEventType.SAGA_NOTIFICATION;
            };

            // Build context from payload
            Map<String, Object> ctx = new HashMap<>();
            payload.fields().forEachRemaining(entry ->
                    ctx.put(entry.getKey(), entry.getValue().asText()));

            // Process the notification (in-app + local channels)
            boolean success = processingService.process(
                    eventType, userId, eventId, ctx,
                    traceId, "saga.commands");

            // Dispatch via AWS Lambda for email/SMS/push
            if (success && !notificationDispatchTopicArn.isBlank()) {
                publishDispatchToSns(sagaId, eventId, userId, eventType, payload, command);
            }

            // Publish SAGA reply
            publishSagaReply(sagaId, commandType, success, traceId);

            ack.acknowledge();
            obs.event(Observation.Event.of("saga.command.processed"));

            log.info("SAGA notification processed: sagaId={} type={} " +
                    "success={}", sagaId, commandType, success);

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process SAGA command: {}",
                    e.getMessage(), e);
            // Do NOT ack — Kafka redelivers
        } finally {
            obs.stop();
        }
    }

    private void publishSagaReply(String sagaId, String commandType,
                                  boolean success, String traceId) {
        try {
            Map<String, Object> reply = Map.of(
                    "replyType", "NotificationSentReply",
                    "sagaId", sagaId,
                    "sourceService", "nexus-notification-service",
                    "success", success,
                    "originalCommand", commandType,
                    "sentAt", Instant.now().toString(),
                    "traceId", traceId
            );

            kafkaTemplate.send("saga.replies", sagaId,
                    objectMapper.writeValueAsString(reply));

        } catch (Exception e) {
            log.error("Failed to publish SAGA reply for sagaId={}: {}",
                    sagaId, e.getMessage());
        }
    }

    private void publishDispatchToSns(String sagaId, String notificationId,
                                      String userId, NotificationEventType eventType,
                                      JsonNode payload, JsonNode command) {
        try {
            String channel = resolveChannel(eventType, payload);

            ObjectNode dispatch = objectMapper.createObjectNode()
                    .put("dispatchId", UUID.randomUUID().toString())
                    .put("notificationId", notificationId)
                    .put("userId", userId)
                    .put("channel", channel)
                    .put("priority", isUrgentEvent(eventType) ? "HIGH" : "NORMAL")
                    .put("eventType", eventType.name())
                    .put("toEmail", payload.path("email").asText(""))
                    .put("subject", payload.path("subject").asText(eventType.name()))
                    .put("htmlBody", payload.path("htmlBody").asText(""))
                    .put("textBody", payload.path("textBody").asText(payload.path("message").asText("")))
                    .put("toPhoneNumber", payload.path("phoneNumber").asText(""))
                    .put("smsBody", payload.path("smsBody").asText(payload.path("message").asText("")))
                    .put("pushTitle", payload.path("pushTitle").asText(eventType.name()))
                    .put("pushBody", payload.path("pushBody").asText(payload.path("message").asText("")))
                    .put("deviceEndpointArn", payload.path("deviceEndpointArn").asText(""))
                    .put("language", payload.path("language").asText("es"));

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
                                    .stringValue(eventType.name())
                                    .build()))
                    .build());

            log.info("Notification dispatched to SNS: sagaId={} channel={} userId={}",
                    sagaId, channel, userId);

        } catch (Exception e) {
            log.error("Failed to publish dispatch to SNS for sagaId={}: {}",
                    sagaId, e.getMessage());
        }
    }

    private String resolveChannel(NotificationEventType eventType, JsonNode payload) {
        if (!payload.path("email").asText("").isBlank()) return "EMAIL";
        if (!payload.path("phoneNumber").asText("").isBlank()) return "SMS";
        if (!payload.path("deviceEndpointArn").asText("").isBlank()) return "PUSH";
        return switch (eventType) {
            case FRAUD_ALERT, TRANSACTION_COMPLETED -> "EMAIL";
            case WELCOME -> "EMAIL";
            default -> "EMAIL";
        };
    }

    private boolean isUrgentEvent(NotificationEventType eventType) {
        return eventType == NotificationEventType.FRAUD_ALERT;
    }
}