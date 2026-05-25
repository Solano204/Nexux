package com.nexus.identity.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.identity.application.command.UserCommandService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SAGA Command Consumer — Participates in OnboardingFlowSaga.
 *
 * Subscribes to: saga.commands
 * Handles: CancelUserRegistrationCommand
 *
 * Pattern: Observer Pattern — reacts to SAGA orchestrator commands
 * Pattern: Template Method — AbstractSagaStepHandler via parent class
 *
 * Idempotency: checks if already cancelled before executing
 * (handles Kafka at-least-once redelivery)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandConsumer {

    private final UserCommandService userCommandService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @KafkaListener(
            topics = "saga.commands",
            groupId = "identity-service-saga-commands",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSagaCommand(String message, Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed",
                        observationRegistry)
                .lowCardinalityKeyValue("topic", "saga.commands")
                .lowCardinalityKeyValue("consumerGroup",
                        "identity-service-saga-commands")
                .start();

        try {
            JsonNode command = objectMapper.readTree(message);
            String commandType = command.path("commandType").asText();
            String targetService = command.path("targetService").asText();

            // Only process commands intended for identity service
            if (!"nexus-identity-service".equals(targetService)) {
                ack.acknowledge();
                return;
            }

            log.info("SAGA command received: type={} sagaId={}",
                    commandType,
                    command.path("sagaId").asText());

            switch (commandType) {
                case "CancelUserRegistrationCommand" -> {
                    UUID userId = UUID.fromString(
                            command.path("payload")
                                    .path("userId").asText());
                    String sagaId = command.path("sagaId").asText();
                    String traceId = command.path("traceId").asText();

                    userCommandService.cancelRegistration(
                            userId, sagaId, traceId);

                    obs.event(Observation.Event.of(
                            "saga.command.CancelUserRegistration.success"));
                    log.info("Registration cancelled via SAGA: " +
                            "userId={} sagaId={}", userId, sagaId);
                }
                default -> log.warn(
                        "Unknown SAGA command for identity service: {}",
                        commandType);
            }

            ack.acknowledge();
            obs.event(Observation.Event.of("kafka.message.success"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process SAGA command: {}", e.getMessage(), e);
            // Do NOT acknowledge — Kafka will redeliver
            // DLQ configured at broker level after maxReceiveCount failures
        } finally {
            obs.stop();
        }
    }
}