package com.nexus.identity.domain.model;

import com.nexus.identity.domain.model.enums.KycDecision;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "kyc_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycVerification {

    @Id
    @Column(name = "verification_id", updatable = false)
    private UUID verificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Column(name = "document_type")
    private String documentType;

    @Column(name = "document_s3_path")
    private String documentS3Path;

    @Column(name = "document_s3_bucket")
    private String documentS3Bucket;

    @Type(JsonBinaryType.class)
    @Column(name = "rekognition_result", columnDefinition = "jsonb")
    private Map<String, Object> rekognitionResult;

    @Type(JsonBinaryType.class)
    @Column(name = "ai_extracted_data", columnDefinition = "jsonb")
    private Map<String, Object> aiExtractedData;

    @Type(JsonBinaryType.class)
    @Column(name = "ai_verification_decision", columnDefinition = "jsonb")
    private Map<String, Object> aiVerificationDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_decision")
    private KycDecision finalDecision;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "failure_reasons", columnDefinition = "text[]")
    private List<String> failureReasons;

    @Column(name = "initiated_at", updatable = false)
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (verificationId == null) verificationId = UUID.randomUUID();
        if (initiatedAt == null) initiatedAt = Instant.now();
        if (finalDecision == null) finalDecision = KycDecision.PENDING;
    }
}