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
public class IdentityEventConsumer {

    private final NotificationProcessingService processingService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "users.registered",
            groupId = "notification-service-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserRegistered(String message,
                                      Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            processIdentityEvent(event,
                    NotificationEventType.WELCOME, ack);
        } catch (Exception e) {
            log.error("users.registered: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "identity.verified",
            groupId = "notification-service-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIdentityVerified(String message,
                                        Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            processIdentityEvent(event,
                    NotificationEventType.KYC_APPROVED, ack);
        } catch (Exception e) {
            log.error("identity.verified: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "identity.rejected",
            groupId = "notification-service-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIdentityRejected(String message,
                                        Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            processIdentityEvent(event,
                    NotificationEventType.KYC_REJECTED, ack);
        } catch (Exception e) {
            log.error("identity.rejected: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "accounts.created",
            groupId = "notification-service-accounts",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAccountCreated(String message,
                                      Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String eventId = event.path("accountId").asText();
            String traceId = event.path("traceId").asText();

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("accountType", event.path("accountType").asText());
            ctx.put("accountId", eventId);
            // Show only last 4 digits
            String acctNum = event.path("accountNumber").asText();
            ctx.put("last4", acctNum.length() >= 4
                    ? acctNum.substring(acctNum.length() - 4) : acctNum);

            processingService.process(
                    NotificationEventType.ACCOUNT_CREATED,
                    userId, eventId, ctx, traceId,
                    "accounts.created");

            ack.acknowledge();

        } catch (Exception e) {
            log.error("accounts.created: {}", e.getMessage(), e);
        }
    }

    private void processIdentityEvent(JsonNode event,
                                      NotificationEventType type,
                                      Acknowledgment ack) {
        String userId = event.path("userId").asText();
        String eventId = event.path("verificationId")
                .asText(userId);
        String traceId = event.path("traceId").asText();

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("userId", userId);
        ctx.put("fullName", event.path("fullName").asText(""));

        if (type == NotificationEventType.KYC_REJECTED) {
            var reasons = event.path("failureReasons");
            if (!reasons.isMissingNode()) {
                ctx.put("rejectionReasons", reasons.toString());
            }
            ctx.put("userMessage",
                    event.path("userMessage").asText(""));
        }

        processingService.process(type, userId, eventId,
                ctx, traceId, "identity-event");
        ack.acknowledge();
    }
}