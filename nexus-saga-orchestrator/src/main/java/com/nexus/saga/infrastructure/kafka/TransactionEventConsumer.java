package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final TransferSagaProcessor transferProcessor;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    private static final java.util.Set<String> TRANSFER_TYPES = java.util.Set.of(
            "INTERNAL_TRANSFER", "EXTERNAL_TRANSFER", "PAYMENT",
            "DIRECT_DEPOSIT", "CASH_IN");

    @KafkaListener(
            topics = "transactions.initiated",
            groupId = "saga-orchestrator-transactions",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTransactionInitiated(ConsumerRecord<String, String> record,
                                            Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "saga-orchestrator-transactions", "transactions.initiated receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            com.fasterxml.jackson.databind.JsonNode event = objectMapper.readTree(message);
            String txnType = event.path("transactionType").asText("");
            if (!TRANSFER_TYPES.contains(txnType)) {
                log.debug("Skipping non-transfer saga for type={}", txnType);
                ack.acknowledge();
                return;
            }
            transferProcessor.handleTransactionInitiated(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to start TransferSaga: {}", e.getMessage(), e);
            // Rethrow so the container's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure - previously swallowed
            // here with no ack, which just silently stalled this record until
            // a restart/rebalance instead of a bounded retry-then-DLT policy.
            throw new RuntimeException("Failed to start TransferSaga", e);
        } finally {
            span.end();
        }
    }
}