package com.nexus.kyc.infrastructure.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * KYC Audit Entry JPA Entity — PostgreSQL kyc_audit_entries table.
 *
 * Schema from V1__create_kyc_audit.sql.
 * IMMUTABLE: PostgreSQL trigger prevent_kyc_audit_modification()
 * raises exception on UPDATE or DELETE.
 *
 * 7-year retention per CNBV regulatory requirements.
 * retention_until = submitted_at + 7 years.
 */
@Entity
@Table(name = "kyc_audit_entries",
        indexes = {
                @Index(name = "idx_kyc_audit_user",
                        columnList = "user_id, submitted_at DESC"),
                @Index(name = "idx_kyc_audit_verification",
                        columnList = "verification_id"),
                @Index(name = "idx_kyc_audit_decision",
                        columnList = "decision, submitted_at DESC")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycAuditEntryJPA {

    @Id
    @Column(name = "audit_id", columnDefinition = "uuid")
    private UUID auditId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "verification_id", nullable = false, columnDefinition = "uuid")
    private UUID verificationId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    // Document metadata
    @Column(name = "document_type", nullable = false, length = 30)
    private String documentType;

    @Column(name = "document_storage_ref", length = 500)
    private String documentStorageRef;

    // Stage 1 AI extraction results
    @Column(name = "stage1_extraction_confidence")
    private Double stage1ExtractionConfidence;

    @Column(name = "stage1_duration_ms")
    private Integer stage1DurationMs;

    // Stage 2 AI comparison results
    @Column(name = "stage2_comparison_confidence")
    private Double stage2ComparisonConfidence;

    @Column(name = "stage2_duration_ms")
    private Integer stage2DurationMs;

    // Decision
    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "rejection_reasons", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] rejectionReasons;

    @Column(name = "user_facing_message", columnDefinition = "text")
    private String userFacingMessage;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    // Field-level match results
    @Column(name = "field_matches", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String fieldMatches;

    // Hashed PII (SHA-256 — never store plaintext)
    @Column(name = "submitted_name_hash", length = 64)
    private String submittedNameHash;

    @Column(name = "submitted_dob_hash", length = 64)
    private String submittedDobHash;

    @Column(name = "extracted_name_hash", length = 64)
    private String extractedNameHash;

    @Column(name = "extracted_dob_hash", length = 64)
    private String extractedDobHash;

    // Provenance
    @Column(name = "ai_model_stage1", nullable = false, length = 50)
    private String aiModelStage1;

    @Column(name = "ai_model_stage2", nullable = false, length = 50)
    private String aiModelStage2;

    @Column(name = "processing_node", length = 100)
    private String processingNode;

    @Column(name = "trace_id", length = 32)
    private String traceId;

    // Timing
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "processing_duration_ms")
    private Integer processingDurationMs;

    // Regulatory
    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;
}

