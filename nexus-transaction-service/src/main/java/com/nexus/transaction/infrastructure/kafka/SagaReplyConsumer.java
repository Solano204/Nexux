package com.nexus.transaction.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.transaction.application.command.TransactionCommandService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Slf4j @Component @RequiredArgsConstructor
public class SagaReplyConsumer {
    private final TransactionCommandService commandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(topics = "saga.replies", groupId = "transaction-service-saga-replies", containerFactory = "kafkaListenerContainerFactory")
    public void consumeSagaReply(String message, Acknowledgment ack) {
        Observation obs = Observation.createNotStarted("kafka.message.processed", observationRegistry).lowCardinalityKeyValue("topic", "saga.replies").start();
        try {
            JsonNode reply = objectMapper.readTree(message);
            String replyType = reply.path("replyType").asText();
            if (!replyType.startsWith("Balance")) { ack.acknowledge(); return; }
            String sourceService = reply.path("sourceService").asText();
            if (!"nexus-account-service".equals(sourceService)) { ack.acknowledge(); return; }
            UUID transactionId = UUID.fromString(reply.path("transactionId").asText());
            UUID sagaId = UUID.fromString(reply.path("sagaId").asText());
            boolean success = reply.path("success").asBoolean();
            String traceId = reply.path("traceId").asText("no-trace");
            String failureReason = !success ? reply.path("payload").path("reason").asText() : null;
            log.info("SAGA balance reply: type={} txnId={} success={}", replyType, transactionId, success);
            commandService.processBalanceResult(transactionId, sagaId, success, failureReason, traceId);
            ack.acknowledge();
        } catch (Exception e) { obs.error(e); log.error("Failed to process saga reply: {}", e.getMessage(), e); } finally { obs.stop(); }
    }
}