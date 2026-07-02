package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final TransferSagaProcessor transferProcessor;
    private final ObjectMapper objectMapper;

    private static final java.util.Set<String> TRANSFER_TYPES = java.util.Set.of(
            "INTERNAL_TRANSFER", "EXTERNAL_TRANSFER", "PAYMENT",
            "DIRECT_DEPOSIT", "CASH_IN");

    @KafkaListener(
            topics = "transactions.initiated",
            groupId = "saga-orchestrator-transactions",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTransactionInitiated(String message, Acknowledgment ack) {
        try {
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
        }
    }
}