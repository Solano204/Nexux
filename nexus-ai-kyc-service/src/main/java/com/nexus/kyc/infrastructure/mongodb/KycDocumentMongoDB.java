package com.nexus.kyc.infrastructure.mongodb;

import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.KycVerificationDecision;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * KycDocumentMongoDB — MongoDB kyc_documents collection.
 *
 * Stores the full operational KYC processing record:
 * - Submitted user data
 * - Rekognition structural analysis results
 * - Stage 1: GPT-4o Vision extraction (KycExtractedData)
 * - Stage 2: GPT-4o-mini comparison (KycVerificationDecision)
 * - Final outcome, retry tracking, SAGA state
 *
 * Encrypted document images stored separately in GridFS (AES-256).
 * Operational TTL: 90 days. Regulatory record in PostgreSQL: 7 years.
 *
 * Indexes:
 *   - userId                        (findByUserId queries)
 *   - submittedAt                   (time-range queries)
 *   - (userId, status) compound     (retry count queries)
 */
@Document(collection = "kyc_documents")
@CompoundIndexes({
        @CompoundIndex(name = "userId_status_idx",
                def = "{'userId': 1, 'status': 1}")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycDocumentMongoDB {

    // ── Primary key ──────────────────────────────────────────
    @Id
    private String verificationId;

    // ── Identity ─────────────────────────────────────────────
    @Indexed
    private String userId;

    private KycStatus status;
    private DocumentType documentType;
    private int attemptNumber;
    private int maxAttempts;

    // ── What the user submitted ───────────────────────────────
    private SubmittedData submittedData;

    // ── Rekognition pre-analysis ──────────────────────────────
    private RekognitionResult rekognitionResult;

    // ── Stage 1: AI vision extraction ────────────────────────
    private KycExtractedData extractedData;
    private long stage1DurationMs;
    private String stage1Model;

    // ── Stage 2: AI comparison decision ──────────────────────
    private KycVerificationDecision decision;
    private long stage2DurationMs;
    private String stage2Model;

    // ── Document storage references ───────────────────────────
    private String documentS3Path;
    private String encryptedGridfsId;
    private String documentHash;          // SHA-256 integrity check

    // ── Hard rule pre-screening ───────────────────────────────
    private boolean passedHardRules;
    private List<String> hardRuleFailures;

    // ── SAGA / tracing ────────────────────────────────────────
    private String traceId;
    private String sagaId;
    private String sagaStatus;

    // ── Timestamps ────────────────────────────────────────────
    @Indexed
    private Instant submittedAt;
    private Instant decidedAt;
    private Instant expiresAt;            // TTL: 90 days operational

    // ── Processing breakdown (ms) ─────────────────────────────
    private ProcessingTiming processingTiming;

    // ── Embedded value objects ────────────────────────────────

    /** Identity data as submitted by the user. */
    @Builder
    public record SubmittedData(
            String fullName,
            String dateOfBirth,
            String documentType,
            String documentNumber,
            String nationality,
            String language
    ) {}

    /** Raw Rekognition analysis results (structural pre-check). */
    @Builder
    public record RekognitionResult(
            boolean faceDetected,
            double faceQualityBrightness,
            double faceQualitySharpness,
            int detectedTextCount,
            int totalCharacters,
            double averageTextConfidence,
            String consolidatedText,
            long processingTimeMs
    ) {}

    /** Millisecond breakdown per pipeline stage. */
    @Builder
    public record ProcessingTiming(
            long totalMs,
            long s3DownloadMs,
            long rekognitionMs,
            long stage1AiMs,
            long stage2AiMs,
            long persistenceMs
    ) {}
}