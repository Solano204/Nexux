package com.nexus.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.notification.application.NotificationProcessingService;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Transaction Event Consumer — SAGA choreography participant.
 *
 * Subscribes to:
 * - transactions.completed → both sender AND receiver notified
 * - transactions.failed    → sender only notified (no charge)
 *
 * Each notification processes independently on a Virtual Thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final NotificationProcessingService processingService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "transactions.completed",
            groupId = "notification-service-transactions",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTransactionCompleted(String message,
                                            Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "transactions.completed")
                .start();

        try {
            JsonNode event = objectMapper.readTree(message);
            String traceId = event.path("traceId").asText("no-trace");

            String sourceUserId = event.path("userId").asText();
            String targetUserId = event.path("targetUserId").asText();
            String transactionId = event.path("transactionId").asText();

            // Notify SENDER
            Map<String, Object> senderCtx = buildTransactionContext(
                    event, "sender");
            processingService.process(
                    NotificationEventType.TRANSACTION_COMPLETED,
                    sourceUserId, transactionId, senderCtx,
                    traceId, "transactions.completed");

            // Notify RECEIVER (if different user)
            if (!targetUserId.isBlank() &&
                    !targetUserId.equals(sourceUserId)) {
                Map<String, Object> receiverCtx =
                        buildTransactionContext(event, "receiver");
                processingService.process(
                        NotificationEventType.TRANSACTION_COMPLETED,
                        targetUserId, transactionId + "-recv",
                        receiverCtx, traceId, "transactions.completed");
            }

            ack.acknowledge();
            obs.event(Observation.Event.of("transaction.completed.processed"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process transaction.completed: {}",
                    e.getMessage(), e);
        } finally {
            obs.stop();
        }
    }

    @KafkaListener(
            topics = "transactions.failed",
            groupId = "notification-service-transactions",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTransactionFailed(String message,
                                         Acknowledgment ack) {

        try {
            JsonNode event = objectMapper.readTree(message);
            String userId = event.path("userId").asText();
            String transactionId = event.path("transactionId").asText();
            String traceId = event.path("traceId").asText("no-trace");

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("amount", event.path("amount").asText());
            ctx.put("currency", event.path("currency").asText("MXN"));
            ctx.put("recipient", event.path("targetAccountNumber")
                    .asText(""));
            ctx.put("failureReason",
                    mapFailureReason(event.path("failureReason").asText()));
            ctx.put("transactionId", transactionId);

            processingService.process(
                    NotificationEventType.TRANSACTION_FAILED,
                    userId, transactionId, ctx, traceId,
                    "transactions.failed");

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process transaction.failed: {}",
                    e.getMessage(), e);
        }
    }

    private Map<String, Object> buildTransactionContext(
            JsonNode event, String role) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("amount", event.path("amount").asText());
        ctx.put("currency", event.path("currency").asText("MXN"));
        ctx.put("transactionId", event.path("transactionId").asText());
        ctx.put("transactionType",
                event.path("transactionType").asText());

        if ("sender".equals(role)) {
            ctx.put("recipient",
                    event.path("targetAccountNumber").asText(""));
            ctx.put("newBalance",
                    event.path("sourceNewBalance").asText(""));
        } else {
            ctx.put("sender",
                    event.path("sourceAccountNumber").asText(""));
            ctx.put("newBalance",
                    event.path("targetNewBalance").asText(""));
        }
        ctx.put("role", role);
        return ctx;
    }

    private String mapFailureReason(String technical) {
        return switch (technical) {
            case "FRAUD_REJECTED" ->
                    "Nuestros sistemas de seguridad bloquearon esta transacción para proteger tu cuenta.";
            case "INSUFFICIENT_FUNDS" ->
                    "No había fondos suficientes para completar la transferencia.";
            case "TIMEOUT" ->
                    "La transferencia no pudo procesarse por un retraso técnico. No se realizó ningún cargo.";
            default ->
                    "La transferencia no pudo completarse. No se realizó ningún cargo.";
        };
    }
}