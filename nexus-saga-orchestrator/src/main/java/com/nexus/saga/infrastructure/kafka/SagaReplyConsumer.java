package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
import com.nexus.saga.application.onboarding.OnboardingFlowSagaProcessor;
import com.nexus.saga.domain.exception.InvalidSagaStateException;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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

    @KafkaListener(
            topics = "saga.replies",
            groupId = "saga-orchestrator-replies",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeReply(String message, Acknowledgment ack) {
        try {
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
                case "KycApprovedReply" ->
                        onboardingProcessor.handleKycApproved(reply);
                case "KycRejectedReply" ->
                        onboardingProcessor.handleKycRejected(reply);

                default -> log.warn("Unknown reply type: {}",
                        replyType);
            }

            ack.acknowledge();

        } catch (InvalidSagaStateException e) {
            log.warn("Skipping stale reply for terminal saga: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process saga reply: {}",
                    e.getMessage(), e);
            // Do NOT acknowledge — Kafka redelivers
        }
    }
}

