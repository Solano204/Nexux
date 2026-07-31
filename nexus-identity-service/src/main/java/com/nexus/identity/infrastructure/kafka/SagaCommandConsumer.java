package com.nexus.identity.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.identity.application.command.UserCommandService;
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
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(
            topics = "saga.commands",
            groupId = "identity-service-saga-commands",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSagaCommand(ConsumerRecord<String, String> record,
                                   Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "identity-service-saga-commands", "saga.commands receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            consumeSagaCommandTraced(message, ack);
        } finally {
            span.end();
        }
    }

    private void consumeSagaCommandTraced(String message, Acknowledgment ack) {

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
            // Rethrow so KafkaConfig's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure and applies the bounded
            // 3-retry-then-DLT policy - swallowing it here (and just not
            // acking) meant the container-level retry+DLT never triggered;
            // redelivery only happened on a restart/rebalance, an unbounded
            // wait, not a real DLQ (no broker-level DLQ actually exists).
            throw new RuntimeException("Failed to process SAGA command", e);
        } finally {
            obs.stop();
        }
    }
}