package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.onboarding.OnboardingFlowSagaProcessor;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityEventConsumer {

    private final OnboardingFlowSagaProcessor onboardingProcessor;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(
            topics = "users.registered",
            groupId = "saga-orchestrator-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserRegistered(ConsumerRecord<String, String> record,
                                      Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "saga-orchestrator-identity", "users.registered receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            onboardingProcessor.handleUserRegistered(objectMapper.readTree(message));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to start OnboardingFlowSaga: {}", e.getMessage(), e);
            // Rethrow so the container's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure - previously swallowed
            // here with no ack, which just silently stalled this record until
            // a restart/rebalance instead of a bounded retry-then-DLT policy.
            throw new RuntimeException("Failed to start OnboardingFlowSaga", e);
        } finally {
            span.end();
        }
    }

    @KafkaListener(
            topics = {"identity.verified", "identity.rejected"},
            groupId = "saga-orchestrator-kyc",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeKycResult(ConsumerRecord<String, String> record,
                                 Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "saga-orchestrator-kyc", "identity.verified/rejected receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText("");
            if (eventType.contains("verified")) {
                onboardingProcessor.handleKycApproved(event);
            } else {
                onboardingProcessor.handleKycRejected(event);
            }
            ack.acknowledge();
        } catch (ObjectOptimisticLockingFailureException e) {
            // Not SagaReplyConsumer anymore (that dual-publish path was
            // removed - see CHANGES-BESTPRACTICES/
            // 08_EVENT_DESIGN_CHANGES.md Section 6). Still reachable via
            // Kafka at-least-once redelivery of this same topic (rebalance
            // mid-processing, etc.), or the dual Rekognition/OpenAI KYC
            // analysis pipelines both completing for the same verification
            // (see OnboardingFlowSagaProcessor.handleKycApproved) - either
            // way, safe to ack.
            span.tag("nexus.idempotency.duplicate", "true");
            log.debug("KYC result already processed concurrently, acknowledging: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process KYC result: {}", e.getMessage(), e);
            // Rethrow so the container's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure - previously swallowed
            // here with no ack, which just silently stalled this record until
            // a restart/rebalance instead of a bounded retry-then-DLT policy.
            throw new RuntimeException("Failed to process KYC result", e);
        } finally {
            span.end();
        }
    }
}