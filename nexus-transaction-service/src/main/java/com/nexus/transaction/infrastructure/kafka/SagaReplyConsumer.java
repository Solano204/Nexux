package com.nexus.transaction.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.tracing.kafka.KafkaTracePropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j @Component @RequiredArgsConstructor
public class SagaReplyConsumer {
    private final TransactionCommandService commandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(topics = "saga.replies", groupId = "transaction-service-saga-replies", containerFactory = "kafkaListenerContainerFactory")
    public void consumeSagaReply(ConsumerRecord<String, String> record,
                                 Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "transaction-service-saga-replies", "saga.replies receive");
        try (Tracer.SpanInScope ignoredScope = tracer.withSpan(span)) {
        consumeSagaReplyTraced(message, ack);
        } finally {
            span.end();
        }
    }

    private void consumeSagaReplyTraced(String message, Acknowledgment ack) {
        // Tag-key set (topic, consumerGroup) must match every other manual
        // "kafka.message.processed" observation platform-wide - this one
        // was missing consumerGroup, which is what caused Micrometer's
        // "already an existing meter... different tag keys" registration
        // conflict (Prometheus requires a consistent tag-key set per meter
        // name within one registry/JVM).
        Observation obs = Observation.createNotStarted("kafka.message.processed", observationRegistry).lowCardinalityKeyValue("topic", "saga.replies").lowCardinalityKeyValue("consumerGroup", "transaction-service-saga-replies").start();
        try {
            JsonNode reply = objectMapper.readTree(message);
            String replyType = reply.path("replyType").asText();
            String sourceService = reply.path("sourceService").asText();
            String transactionIdStr = reply.path("transactionId").asText("");
            if (transactionIdStr.isEmpty()) { ack.acknowledge(); return; }
            UUID transactionId = UUID.fromString(transactionIdStr);
            UUID sagaId = UUID.fromString(reply.path("sagaId").asText());
            String traceId = reply.path("traceId").asText("no-trace");

            if (("BalanceReservedReply".equals(replyType) || "BalanceReservationFailedReply".equals(replyType))
                    && "nexus-account-service".equals(sourceService)) {
                boolean success = "BalanceReservedReply".equals(replyType);
                String failureReason = !success ? reply.path("payload").path("reason").asText() : null;
                log.info("SAGA balance reply: type={} txnId={} success={}", replyType, transactionId, success);
                commandService.processBalanceResult(transactionId, sagaId, success, failureReason, traceId);

            } else if ("LedgerPostedReply".equals(replyType) && "nexus-ledger-service".equals(sourceService)) {
                UUID postingId = UUID.fromString(reply.path("postingId").asText());
                log.info("SAGA ledger posted: txnId={} postingId={}", transactionId, postingId);
                commandService.processLedgerResult(transactionId, sagaId, true, postingId, null, traceId);

            } else if ("FraudClearedReply".equals(replyType) && "nexus-fraud-service".equals(sourceService)) {
                BigDecimal score = new BigDecimal(reply.path("riskScore").asText("0"));
                List<String> reasons = new ArrayList<>();
                reply.path("reasons").forEach(r -> reasons.add(r.asText()));
                log.info("SAGA fraud cleared: txnId={} score={}", transactionId, score);
                commandService.processFraudResult(transactionId, sagaId, true, score, reasons, traceId);

            } else if ("FraudRejectedReply".equals(replyType) && "nexus-fraud-service".equals(sourceService)) {
                BigDecimal score = new BigDecimal(reply.path("riskScore").asText("100"));
                List<String> reasons = new ArrayList<>();
                reply.path("reasons").forEach(r -> reasons.add(r.asText()));
                log.info("SAGA fraud rejected: txnId={} score={}", transactionId, score);
                commandService.processFraudResult(transactionId, sagaId, false, score, reasons, traceId);

            } else {
                ack.acknowledge();
                return;
            }

            ack.acknowledge();
        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process saga reply: {}", e.getMessage(), e);
            // Rethrow so KafkaConfig's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure and applies the bounded
            // 3-retry-then-DLT policy, instead of an unbounded wait for a
            // restart/rebalance to redeliver.
            throw new RuntimeException("Failed to process saga reply", e);
        } finally {
            obs.stop();
        }
    }
}