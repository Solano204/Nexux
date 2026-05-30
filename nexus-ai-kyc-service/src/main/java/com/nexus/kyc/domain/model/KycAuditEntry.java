package com.nexus.kyc.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * KYC Audit Entry — immutable domain record for the regulatory audit trail.
 *
 * Maps to PostgreSQL kyc_audit_entries table (V1__create_kyc_audit.sql).
 * Immutable: PostgreSQL trigger prevents UPDATE/DELETE.
 * 7-year retention per CNBV regulatory requirements.
 *
 * Each KYC verification produces exactly one audit entry capturing:
 * - What document was analyzed (type, storage ref)
 * - What the AI extracted (confidence scores, durations)
 * - What decision was made (outcome, rejection reasons)
 * - Why (field matches as JSONB, hashed PII)
 * - Provenance (AI model versions, trace ID)
 */
public record KycAuditEntry(
        UUID auditId,
        UUID userId,
        UUID verificationId,
        int attemptNumber,

        // Document metadata
        String documentType,
        String documentStorageRef,

        // Stage 1 results
        Double stage1ExtractionConfidence,
        Integer stage1DurationMs,

        // Stage 2 results
        Double stage2ComparisonConfidence,
        Integer stage2DurationMs,

        // Decision
        String decision,            // APPROVED, REJECTED, REVIEW_REQUIRED, FAILED
        String[] rejectionReasons,
        String userFacingMessage,
        Double confidenceScore,

        // Field match details (stored as JSONB string)
        String fieldMatches,

        // Hashed PII (SHA-256 — never store plaintext in audit)
        String submittedNameHash,
        String submittedDobHash,
        String extractedNameHash,
        String extractedDobHash,

        // Provenance
        String aiModelStage1,
        String aiModelStage2,
        String processingNode,
        String traceId,

        // Timing
        Instant submittedAt,
        Instant decidedAt,
        Integer processingDurationMs,

        // Regulatory
        Instant retentionUntil
) {

    /**
     * Builder-style factory for common construction pattern.
     */
    public static KycAuditEntryBuilder builder() {
        return new KycAuditEntryBuilder();
    }

    public static class KycAuditEntryBuilder {
        private UUID auditId;
        private UUID userId;
        private UUID verificationId;
        private int attemptNumber;
        private String documentType;
        private String documentStorageRef;
        private Double stage1ExtractionConfidence;
        private Integer stage1DurationMs;
        private Double stage2ComparisonConfidence;
        private Integer stage2DurationMs;
        private String decision;
        private String[] rejectionReasons;
        private String userFacingMessage;
        private Double confidenceScore;
        private String fieldMatches;
        private String submittedNameHash;
        private String submittedDobHash;
        private String extractedNameHash;
        private String extractedDobHash;
        private String aiModelStage1;
        private String aiModelStage2;
        private String processingNode;
        private String traceId;
        private Instant submittedAt;
        private Instant decidedAt;
        private Integer processingDurationMs;
        private Instant retentionUntil;

        public KycAuditEntryBuilder auditId(UUID v) { this.auditId = v; return this; }
        public KycAuditEntryBuilder userId(UUID v) { this.userId = v; return this; }
        public KycAuditEntryBuilder verificationId(UUID v) { this.verificationId = v; return this; }
        public KycAuditEntryBuilder attemptNumber(int v) { this.attemptNumber = v; return this; }
        public KycAuditEntryBuilder documentType(String v) { this.documentType = v; return this; }
        public KycAuditEntryBuilder documentStorageRef(String v) { this.documentStorageRef = v; return this; }
        public KycAuditEntryBuilder stage1ExtractionConfidence(Double v) { this.stage1ExtractionConfidence = v; return this; }
        public KycAuditEntryBuilder stage1DurationMs(Integer v) { this.stage1DurationMs = v; return this; }
        public KycAuditEntryBuilder stage2ComparisonConfidence(Double v) { this.stage2ComparisonConfidence = v; return this; }
        public KycAuditEntryBuilder stage2DurationMs(Integer v) { this.stage2DurationMs = v; return this; }
        public KycAuditEntryBuilder decision(String v) { this.decision = v; return this; }
        public KycAuditEntryBuilder rejectionReasons(String[] v) { this.rejectionReasons = v; return this; }
        public KycAuditEntryBuilder userFacingMessage(String v) { this.userFacingMessage = v; return this; }
        public KycAuditEntryBuilder confidenceScore(Double v) { this.confidenceScore = v; return this; }
        public KycAuditEntryBuilder fieldMatches(String v) { this.fieldMatches = v; return this; }
        public KycAuditEntryBuilder submittedNameHash(String v) { this.submittedNameHash = v; return this; }
        public KycAuditEntryBuilder submittedDobHash(String v) { this.submittedDobHash = v; return this; }
        public KycAuditEntryBuilder extractedNameHash(String v) { this.extractedNameHash = v; return this; }
        public KycAuditEntryBuilder extractedDobHash(String v) { this.extractedDobHash = v; return this; }
        public KycAuditEntryBuilder aiModelStage1(String v) { this.aiModelStage1 = v; return this; }
        public KycAuditEntryBuilder aiModelStage2(String v) { this.aiModelStage2 = v; return this; }
        public KycAuditEntryBuilder processingNode(String v) { this.processingNode = v; return this; }
        public KycAuditEntryBuilder traceId(String v) { this.traceId = v; return this; }
        public KycAuditEntryBuilder submittedAt(Instant v) { this.submittedAt = v; return this; }
        public KycAuditEntryBuilder decidedAt(Instant v) { this.decidedAt = v; return this; }
        public KycAuditEntryBuilder processingDurationMs(Integer v) { this.processingDurationMs = v; return this; }
        public KycAuditEntryBuilder retentionUntil(Instant v) { this.retentionUntil = v; return this; }

        public KycAuditEntry build() {
            return new KycAuditEntry(auditId, userId, verificationId, attemptNumber,
                    documentType, documentStorageRef,
                    stage1ExtractionConfidence, stage1DurationMs,
                    stage2ComparisonConfidence, stage2DurationMs,
                    decision, rejectionReasons, userFacingMessage, confidenceScore,
                    fieldMatches,
                    submittedNameHash, submittedDobHash, extractedNameHash, extractedDobHash,
                    aiModelStage1, aiModelStage2, processingNode, traceId,
                    submittedAt, decidedAt, processingDurationMs, retentionUntil);
        }
    }
}