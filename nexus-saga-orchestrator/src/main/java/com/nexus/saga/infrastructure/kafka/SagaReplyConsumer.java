package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
import com.nexus.saga.application.onboarding.OnboardingFlowSagaProcessor;
import com.nexus.saga.domain.exception.InvalidSagaStateException;
import com.nexus.tracing.kafka.KafkaTracePropagation;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Saga Reply Consumer — processes all incoming saga replies.
 *
 * Routes replies to the appropriate saga processor by replyType.
 * Acknowledges offset ONLY after successful processing.
 * On failure: offset NOT committed → Kafka redelivers → idempotent handling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReplyConsumer {

    private final TransferSagaProcessor transferProcessor;
    private final OnboardingFlowSagaProcessor onboardingProcessor;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(
            topics = "saga.replies",
            groupId = "saga-orchestrator-replies",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeReply(ConsumerRecord<String, String> record,
                             Acknowledgment ack) {
        String message = record.value();
        Headers headers = record.headers();
        Span span = KafkaTracePropagation.extractAndStartSpan(
                tracer, propagator, record, "saga-orchestrator-replies", "saga.replies receive");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            JsonNode reply = objectMapper.readTree(message);
            String replyType = reply.path("replyType").asText();

            log.debug("Saga reply received: type={}", replyType);

            switch (replyType) {
                // ── Transfer saga replies ──────────────────
                case "BalanceReservedReply" ->
                        transferProcessor.handleBalanceReserved(reply);
                case "BalanceReservationFailedReply" ->
                        transferProcessor
                                .handleBalanceReservationFailed(reply);
                case "FraudClearedReply" ->
                        transferProcessor.handleFraudCleared(reply);
                case "FraudRejectedReply" ->
                        transferProcessor.handleFraudRejected(reply);
                case "FraudReviewReply" ->
                        transferProcessor.handleFraudReview(reply);
                case "LedgerPostedReply" ->
                        transferProcessor.handleLedgerPosted(reply);
                case "LedgerFailedReply" ->
                        transferProcessor.handleLedgerFailed(reply);
                case "BalanceFinalizedReply" ->
                        transferProcessor.handleBalanceFinalized(reply);
                case "BalanceReleasedReply" ->
                        transferProcessor.handleBalanceReleased(reply);
                case "NotificationSentReply" -> {
                    String originalCommand = reply.path("originalCommand").asText("");
                    if ("SendWelcomeNotificationCommand".equals(originalCommand)) {
                        onboardingProcessor.handleWelcomeNotificationSent(reply);
                    } else {
                        transferProcessor.handleNotificationSent(reply);
                    }
                }

                // ── Onboarding saga replies ────────────────
                case "AccountsCreatedReply" ->
                        onboardingProcessor.handleAccountsCreated(reply);
                case "AccountCreationFailedReply" ->
                        onboardingProcessor
                                .handleAccountCreationFailed(reply);
                // KycApprovedReply/KycRejectedReply removed: ai-kyc-service
                // used to publish these directly (bypassing its own
                // outbox, unreliable) alongside a duplicate, unreliable
                // direct-publish of identity.verified/identity.rejected -
                // IdentityEventConsumer.consumeKycResult already drives
                // onboardingProcessor.handleKycApproved/Rejected from the
                // real (outbox-backed, reliable) identity.verified/
                // identity.rejected topic. See
                // CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 6.

                default -> log.warn("Unknown reply type: {}",
                        replyType);
            }

            ack.acknowledge();

        } catch (InvalidSagaStateException e) {
            span.tag("nexus.idempotency.duplicate", "true");
            log.warn("Skipping stale/duplicate saga reply: {}", e.getMessage());
            ack.acknowledge();
        } catch (ObjectOptimisticLockingFailureException e) {
            span.tag("nexus.idempotency.duplicate", "true");
            log.warn("Concurrent saga reply — already committed by another consumer, acking");
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process saga reply: {}",
                    e.getMessage(), e);
            // Rethrow so KafkaConfig's DefaultErrorHandler(deadLetterRecoverer,
            // FixedBackOff) actually sees this failure and applies the bounded
            // 3-retry-then-DLT policy, instead of an unbounded wait for a
            // restart/rebalance to redeliver.
            throw new RuntimeException("Failed to process saga reply", e);
        } finally {
            span.end();
        }
    }
}

