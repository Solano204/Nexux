package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
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
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(
            topics = "users.registered",
            groupId = "notification-service-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserRegistered(ConsumerRecord<String, String> record,
                                      Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "notification-service-identity", "users.registered receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            JsonNode event = objectMapper.readTree(message);
            processIdentityEvent(event,
                    NotificationEventType.WELCOME, ack);
        } catch (Exception e) {
            log.error("users.registered: {}", e.getMessage(), e);
        } finally {
            span.end();
        }
    }

    @KafkaListener(
            topics = "identity.verified",
            groupId = "notification-service-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIdentityVerified(ConsumerRecord<String, String> record,
                                        Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "notification-service-identity", "identity.verified receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            JsonNode event = objectMapper.readTree(message);
            processIdentityEvent(event,
                    NotificationEventType.KYC_APPROVED, ack);
        } catch (Exception e) {
            log.error("identity.verified: {}", e.getMessage(), e);
        } finally {
            span.end();
        }
    }

    @KafkaListener(
            topics = "identity.rejected",
            groupId = "notification-service-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeIdentityRejected(ConsumerRecord<String, String> record,
                                        Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "notification-service-identity", "identity.rejected receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            JsonNode event = objectMapper.readTree(message);
            processIdentityEvent(event,
                    NotificationEventType.KYC_REJECTED, ack);
        } catch (Exception e) {
            log.error("identity.rejected: {}", e.getMessage(), e);
        } finally {
            span.end();
        }
    }

    @KafkaListener(
            topics = "accounts.created",
            groupId = "notification-service-accounts",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAccountCreated(ConsumerRecord<String, String> record,
                                      Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "notification-service-accounts", "accounts.created receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String eventId = event.path("accountId").asText();
            String traceId = event.path("traceId").asText();

            if (userId.isBlank()) {
                log.warn("accounts.created event arrived without a " +
                        "userId. accountId={}", eventId);
            }

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
        } finally {
            span.end();
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