package com.nexus.fraud.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.application.FraudAnalysisService;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fraud Command Consumer — SAGA participant.
 *
 * Subscribes to: saga.commands
 * Processes: CheckFraudCommand
 *
 * Processing guarantees:
 * - Kafka offset committed ONLY after decision persisted + reply sent
 * - Idempotency check in FraudAnalysisService handles duplicates
 * - Virtual Thread per message (up to 20 concurrent analyses)
 *
 * Pattern: SAGA Choreography participant
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudCommandConsumer {

    private final FraudAnalysisService fraudService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "saga.commands",
            groupId = "fraud-service-saga-commands",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeFraudCommand(String message,
                                    Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "saga.commands")
                .lowCardinalityKeyValue("consumerGroup",
                        "fraud-service-saga-commands")
                .start();

        try {
            JsonNode command = objectMapper.readTree(message);
            String commandType = command.path("commandType").asText();
            String targetService = command.path("targetService").asText();

            // Only process commands for fraud service
            if (!"CheckFraudCommand".equals(commandType) ||
                    !"nexus-fraud-service".equals(targetService)) {
                ack.acknowledge();
                return;
            }

            String sagaId = command.path("sagaId").asText();
            String traceId = command.path("traceId").asText("no-trace");
            JsonNode payload = command.path("payload");

            log.info("CheckFraudCommand received: sagaId={} txnId={}",
                    sagaId, payload.path("transactionId").asText());

            // Extract pre-computed signals from payload
            Map<String, Object> signals = new HashMap<>();
            payload.path("preComputedSignals")
                    .fields()
                    .forEachRemaining(entry ->
                            signals.put(entry.getKey(),
                                    entry.getValue().asText()));

            FraudAnalysisRequest request = new FraudAnalysisRequest(
                    payload.path("transactionId").asText(),
                    sagaId,
                    payload.path("userId").asText(),
                    payload.path("sourceAccountId").asText(),
                    payload.path("targetAccountId").asText(""),
                    new BigDecimal(payload.path("amount").asText("0")),
                    payload.path("currency").asText("MXN"),
                    payload.path("transactionType").asText(),
                    payload.path("description").asText(""),
                    payload.path("merchantId").asText(""),
                    payload.path("merchantName").asText(""),
                    payload.path("merchantCategoryCode").asText(""),
                    payload.path("sourceIp").asText(""),
                    payload.path("deviceFingerprint").asText(""),
                    payload.path("isKnownDevice").asBoolean(false),
                    payload.path("accountAgeDays").asInt(0),
                    payload.path("totalTransactionCount").asInt(0),
                    signals,
                    traceId
            );

            // Execute fraud analysis (delegates to ReAct agent)
            fraudService.analyze(request);

            // Commit ONLY after full processing
            ack.acknowledge();
            obs.event(Observation.Event.of(
                    "fraud.command.processed"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process fraud command: {}",
                    e.getMessage(), e);
            // Do NOT acknowledge — Kafka will redeliver
        } finally {
            obs.stop();
        }
    }
}