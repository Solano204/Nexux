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

    @KafkaListener(
            topics = "transactions.initiated",
            groupId = "saga-orchestrator-transactions",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTransactionInitiated(String message, Acknowledgment ack) {
        try {
            transferProcessor.handleTransactionInitiated(
                    objectMapper.readTree(message));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to start TransferSaga: {}", e.getMessage(), e);
        }
    }
}