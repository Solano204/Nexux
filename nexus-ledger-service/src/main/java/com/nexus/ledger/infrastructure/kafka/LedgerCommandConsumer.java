package com.nexus.ledger.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.ledger.application.command.LedgerCommandService;
import com.nexus.ledger.application.command.PostLedgerCommand;
import com.nexus.ledger.domain.model.enums.PostingType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ledger Command Consumer — SAGA participant.
 *
 * Subscribes to: saga.commands
 * Processes: PostLedgerCommand
 *
 * After successful posting:
 * - Publishes LedgerPostedReply to saga.replies
 * - Outbox entry triggers Debezium → ledger.posted topic
 *
 * Offset committed ONLY after PostgreSQL commit + reply published.
 * Idempotency in LedgerCommandService handles Kafka redelivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerCommandConsumer {

    private final LedgerCommandService commandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "saga.commands",
            groupId = "ledger-service-saga-commands",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeLedgerCommand(String message,
                                     Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "saga.commands")
                .lowCardinalityKeyValue("consumerGroup",
                        "ledger-service-saga-commands")
                .start();

        try {
            JsonNode command = objectMapper.readTree(message);
            String commandType = command.path("commandType").asText();
            String targetService = command.path("targetService").asText();

            // Only process commands for ledger service
            if (!"PostLedgerCommand".equals(commandType) ||
                    !"nexus-ledger-service".equals(targetService)) {
                ack.acknowledge();
                return;
            }

            String sagaId = command.path("sagaId").asText();
            String traceId = command.path("traceId").asText("no-trace");
            JsonNode payload = command.path("payload");

            log.info("PostLedgerCommand received: sagaId={} txnId={}",
                    sagaId, payload.path("transactionId").asText());

            String postingTypeStr = payload.path("postingType").asText("TRANSFER");
            boolean isDeposit = "DIRECT_DEPOSIT".equals(postingTypeStr) || "CASH_IN".equals(postingTypeStr);

            // Deposits: system external account is the debit side, user's account is the credit side
            UUID sourceAccountId = isDeposit
                    ? UUID.fromString("00000000-0000-0000-0000-000000000001")
                    : UUID.fromString(payload.path("sourceAccountId").asText());
            UUID targetAccountId = UUID.fromString(payload.path("targetAccountId").asText());

            PostLedgerCommand postCommand = PostLedgerCommand.builder()
                    .transactionId(UUID.fromString(
                            payload.path("transactionId").asText()))
                    .sourceAccountId(sourceAccountId)
                    .targetAccountId(targetAccountId)
                    .amount(new BigDecimal(
                            payload.path("amount").asText("0")))
                    .currency(payload.path("currency").asText("MXN"))
                    .postingType(PostingType.valueOf(
                            payload.path("postingType").asText("TRANSFER")))
                    .description(payload.path("description").asText(""))
                    .merchantName(payload.path("merchantName")
                            .asText(null))
                    .merchantCategoryCode(
                            payload.path("merchantCategoryCode").asText(null))
                    .sagaId(sagaId)
                    .traceId(traceId)
                    .build();

            // Execute the double-entry posting
            var result = commandService.postDoubleEntry(postCommand);

            // Publish SAGA reply
            commandService.publishSagaReply(
                    result,
                    postCommand.transactionId(),
                    sagaId, traceId);

            ack.acknowledge();
            obs.event(Observation.Event.of(
                    "ledger.command.processed"));

            log.info("PostLedger processed: sagaId={} postingId={} " +
                            "idempotent={}",
                    sagaId, result.postingId(),
                    result.idempotentReplay());

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process PostLedgerCommand: {}",
                    e.getMessage(), e);
            // Do NOT acknowledge — Kafka will redeliver
        } finally {
            obs.stop();
        }
    }
}