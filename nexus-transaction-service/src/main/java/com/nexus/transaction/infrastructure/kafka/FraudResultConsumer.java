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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fraud Result Consumer — SAGA Choreography step 2.
 *
 * Subscribes to: fraud.result
 * Receives: FraudCheckCompleted events from nexus-fraud-service
 *
 * On CLEARED: advances SAGA → publishes BalanceReservationRequested
 * On REJECTED: terminates SAGA → publishes TransactionFraudRejected
 *
 * Pattern: SAGA Choreography — event-driven state advancement
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudResultConsumer {

    private final TransactionCommandService commandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "fraud.result",
            groupId = "transaction-service-fraud-results",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeFraudResult(String message, Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "fraud.result")
                .start();

        try {
            JsonNode event = objectMapper.readTree(message);

            UUID transactionId = UUID.fromString(
                    event.path("transactionId").asText());
            UUID sagaId = UUID.fromString(
                    event.path("sagaId").asText());
            boolean cleared = "CLEARED".equals(
                    event.path("decision").asText());
            BigDecimal score = new BigDecimal(
                    event.path("fraudScore").asText("0.0"));
            String traceId = event.path("traceId").asText();

            List<String> reasons = new ArrayList<>();
            event.path("reasons").forEach(r ->
                    reasons.add(r.asText()));

            log.info("Fraud result received: txnId={} cleared={} " +
                    "score={}", transactionId, cleared, score);

            commandService.processFraudResult(
                    transactionId, sagaId, cleared,
                    score, reasons, traceId);

            ack.acknowledge();
            obs.event(Observation.Event.of("fraud.result.processed"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process fraud result: {}",
                    e.getMessage(), e);
        } finally {
            obs.stop();
        }
    }
}