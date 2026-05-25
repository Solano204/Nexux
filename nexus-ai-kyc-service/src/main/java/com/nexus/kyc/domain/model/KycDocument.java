package com.nexus.kyc.infrastructure.mongodb;

import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.KycVerificationDecision;
import com.nexus.kyc.domain.model.enums.*;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * KycDocument — MongoDB operational document.
 *
 * Stores the full KYC processing record for operational use:
 * - Retry tracking (how many attempts, last attempt timestamp)
 * - Full extraction results (for debugging and re-processing)
 * - Full decision with all reasoning (for compliance queries)
 * - Processing pipeline metadata (timing, model versions)
 *
 * This is distinct from the PostgreSQL audit entry which stores
 * only the regulatory-required fields in immutable form.
 */
@Document(collection = "kyc_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycDocument {

    @Id
    private String verificationId;

    @Indexed
    private String userId;

    private KycStatus status;
    private DocumentType documentType;
    private int attemptNumber;
    private int maxAttempts;

    // Stage 1 results
    private KycExtractedData extractedData;
    private long stage1DurationMs;

    // Stage 2 results
    private KycVerificationDecision decision;
    private long stage2DurationMs;

    // Document storage reference (S3 or GridFS)
    private String documentStorageRef;
    private String documentHash;        // SHA-256 for integrity

    // Pipeline metadata
    private String stage1Model;
    private String stage2Model;
    private String traceId;

    // Timing
    private Instant submittedAt;
    private Instant decidedAt;
    private Instant expiresAt;          // TTL: 90 days operational

    // SAGA tracking
    private String sagaId;
    private String sagaStatus;

    // Hard rule pre-screening
    private boolean passedHardRules;
    private List<String> hardRuleFailures;
}