package com.nexus.kyc.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.jpa.OutboxEntry;
import com.nexus.kyc.infrastructure.jpa.OutboxRepository;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes the KYC verification result to identity-service via the
 * Outbox pattern (topic "identity.kyc.result"), replacing the two
 * synchronous, non-retried HTTP callbacks that used to do this
 * (KycInitiationConsumer/SqsRekognitionResultConsumer both had their own
 * copy of the same callbackIdentityService() logic - a failed HTTP call
 * silently dropped an already-completed verification, logged and never
 * retried). See CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 6.
 *
 * Writing the outbox row from here (called from
 * KycVerificationService.persistAndPublish, inside the same
 * @Transactional as the kyc_audit_entries write) is what actually makes
 * this reliable - both are the same PostgreSQL database, so they commit
 * together atomically. Doing this write from the Kafka/SQS consumer
 * instead, after verify() already returned, would reopen the exact silent-
 * loss window this change is meant to close.
 */
@Component
@RequiredArgsConstructor
public class KycResultOutboxPublisher {

    private static final String TOPIC = "identity.kyc.result";

    private final ObjectMapper objectMapper;
    private final OutboxRepository outboxRepository;
    private final Tracer tracer;

    /**
     * Real verification decision (approved or rejected by the pipeline).
     * Called from KycVerificationService.persistAndPublish - already
     * inside that method's @Transactional.
     */
    public void publishResult(String userId, String verificationId, KycDocumentMongoDB doc) {
        Map<String, Object> extractedData = new HashMap<>();
        if (doc.getExtractedData() != null) {
            extractedData = objectMapper.convertValue(doc.getExtractedData(), Map.class);
        }

        Map<String, Object> decisionMap = new HashMap<>();
        if (doc.getDecision() != null) {
            decisionMap.put("status", doc.getDecision().status().name());
            decisionMap.put("confidenceScore", doc.getDecision().confidenceScore());
            decisionMap.put("userFacingMessage", doc.getDecision().userFacingRejectionMessage());
        }

        List<String> failureReasons = List.of();
        if (doc.getDecision() != null && doc.getDecision().rejectionReasons() != null) {
            failureReasons = doc.getDecision().rejectionReasons()
                    .stream().map(Enum::name).toList();
        }

        publish(userId, verificationId, doc.getStatus() == KycStatus.APPROVED,
                extractedData, decisionMap, failureReasons);
    }

    /**
     * Processing-failed-before-verification case (e.g. Rekognition itself
     * failed) - no KycDocumentMongoDB was ever produced, so this bypasses
     * KycVerificationService entirely. Called directly from
     * SqsRekognitionResultConsumer, not wrapped in any enclosing business
     * transaction since there is no other durable write alongside it -
     * same as before, just reliable Kafka delivery instead of a bare HTTP
     * POST with zero retry.
     */
    @Transactional
    public void publishFailure(String userId, String verificationId, String reason) {
        publish(userId, verificationId, false, Map.of(),
                Map.of("status", "REJECTED", "userFacingMessage", reason),
                List.of("REKOGNITION_PROCESSING_FAILED"));
    }

    private void publish(String userId, String verificationId, boolean approved,
                         Map<String, Object> extractedData, Map<String, Object> decisionMap,
                         List<String> failureReasons) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", userId);
        payload.put("verificationId", verificationId);
        payload.put("approved", approved);
        payload.set("extractedData", objectMapper.valueToTree(extractedData));
        payload.set("verificationDecision", objectMapper.valueToTree(decisionMap));
        payload.set("failureReasons", objectMapper.valueToTree(failureReasons));
        payload.put("completedAt", Instant.now().toString());

        OutboxEntry entry = OutboxEntry.of(TOPIC, UUID.fromString(userId),
                "KycVerificationCompleted", payload);
        entry.attachTraceContext(tracer);
        outboxRepository.save(entry);
    }
}
