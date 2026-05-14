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

/**
 * Ledger Result Consumer — SAGA Choreography step 4.
 *
 * Subscribes to: ledger.result
 * Receives: LedgerPosted / LedgerFailed events
 *
 * On POSTED: completes transaction → TransactionCompleted
 * On FAILED: releases balance → BalanceReleaseRequested
 *            then fails transaction → TransactionFailed
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerResultConsumer {

    private final TransactionCommandService commandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "ledger.result",
            groupId = "transaction-service-ledger-results",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeLedgerResult(String message, Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "ledger.result")
                .start();

        try {
            JsonNode event = objectMapper.readTree(message);

            UUID transactionId = UUID.fromString(
                    event.path("transactionId").asText());
            UUID sagaId = UUID.fromString(
                    event.path("sagaId").asText());
            boolean success = event.path("success").asBoolean();
            String traceId = event.path("traceId").asText();

            UUID ledgerEntryId = success
                    ? UUID.fromString(event.path("ledgerEntryId").asText())
                    : null;
            String failureReason = !success
                    ? event.path("failureReason").asText()
                    : null;

            log.info("Ledger result received: txnId={} success={}",
                    transactionId, success);

            commandService.processLedgerResult(
                    transactionId, sagaId, success,
                    ledgerEntryId, failureReason, traceId);

            ack.acknowledge();
            obs.event(Observation.Event.of("ledger.result.processed"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process ledger result: {}",
                    e.getMessage(), e);
        } finally {
            obs.stop();
        }
    }
}