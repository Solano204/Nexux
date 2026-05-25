package com.nexus.kyc.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.mongodb.KycDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * KYC Event Producer — publishes SAGA replies + domain events.
 *
 * Via outbox pattern (PostgreSQL kyc_outbox table → Debezium):
 * - identity.verified   → KYC approved
 * - identity.rejected   → KYC rejected
 * - kyc.review.required → Needs human review
 * - saga.replies        → OnboardingFlowSaga continuation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishKycDecision(KycDocument doc, String sagaId) {
        try {

            // 1. Domain event for notification service, analytics, etc.
            String domainTopic = switch (doc.getStatus()) {
                case APPROVED -> "identity.verified";
                case REJECTED -> "identity.rejected";
                case REVIEW_REQUIRED -> "kyc.review.required";
                default -> null;
            };

            if (domainTopic != null) {
                Map<String, Object> domainEvent = Map.of(
                        "eventType", domainTopic,
                        "userId", doc.getUserId(),
                        "verificationId", doc.getVerificationId(),
                        "status", doc.getStatus().name(),
                        "userFacingMessage",
                        doc.getDecision() != null &&
                                doc.getDecision().userFacingRejectionMessage() != null
                                ? doc.getDecision()
                                .userFacingRejectionMessage()
                                : "",
                        "canRetry",
                        doc.getDecision() != null &&
                                doc.getDecision().canRetry(),
                        "occurredAt", Instant.now().toString()
                );

                kafkaTemplate.send(domainTopic, doc.getUserId(),
                        objectMapper.writeValueAsString(domainEvent));
            }

            // 2. SAGA reply
            if (sagaId != null && !sagaId.isBlank()) {
                Map<String, Object> sagaReply = Map.of(
                        "replyType", doc.getStatus() == KycStatus.APPROVED
                                ? "KycApprovedReply" : "KycRejectedReply",
                        "sagaId", sagaId,
                        "userId", doc.getUserId(),
                        "verificationId", doc.getVerificationId(),
                        "success", doc.getStatus() == KycStatus.APPROVED,
                        "status", doc.getStatus().name(),
                        "rejectionReasons",
                        doc.getDecision() != null &&
                                doc.getDecision().rejectionReasons() != null
                                ? doc.getDecision().rejectionReasons()
                                .stream()
                                .map(Enum::name)
                                .toList()
                                : java.util.List.of(),
                        "userFacingMessage",
                        doc.getDecision() != null
                                ? doc.getDecision()
                                .userFacingRejectionMessage()
                                : "",
                        "sourceService", "nexus-ai-kyc-service",
                        "repliedAt", Instant.now().toString()
                );

                kafkaTemplate.send("saga.replies", sagaId,
                        objectMapper.writeValueAsString(sagaReply));

                log.info("KYC SAGA reply published: sagaId={} " +
                        "status={}", sagaId, doc.getStatus());
            }

        } catch (Exception e) {
            log.error("Failed to publish KYC events: {}",
                    e.getMessage(), e);
        }
    }
}