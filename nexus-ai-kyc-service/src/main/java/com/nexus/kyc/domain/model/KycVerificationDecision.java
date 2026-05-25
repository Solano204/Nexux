package com.nexus.kyc.domain.model;

import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import com.nexus.kyc.domain.model.enums.VerificationField;

import java.util.List;
import java.util.Map;

/**
 * KycVerificationDecision — Structured output from Stage 2 AI (Section 3).
 *
 * The comparison LLM receives:
 * - What the user submitted (name, DOB, document number)
 * - What the AI extracted from the image (KycExtractedData)
 *
 * It produces a field-by-field comparison and an overall decision.
 *
 * Design: the LLM handles name normalization intelligently.
 * "JUAN CARLOS GARCIA LOPEZ" vs "Juan Carlos García López" → MATCH.
 * "MARIA ELENA" vs "Maria Gonzalez" → MISMATCH.
 * This fuzzy matching is where AI beats exact string comparison.
 *
 * userFacingRejectionMessage: written in the user's language,
 * actionable, does not reveal fraud detection details.
 */
public record KycVerificationDecision(

        // Overall outcome
        KycStatus status,
        double confidenceScore,         // 0.0-1.0

        // Per-field comparison results
        Map<String, FieldMatchResult> fieldMatches,

        // For REJECTED/REVIEW_REQUIRED
        List<RejectionReason> rejectionReasons,
        String userFacingRejectionMessage,  // In user's language
        boolean canRetry,               // Can user try again?
        int retryDelayHours,            // How long to wait before retry

        // For APPROVED
        String verifiedFullName,        // Canonical name from document
        String verifiedDateOfBirth,     // Normalized to ISO format
        String verifiedDocumentNumber,
        String verifiedNationality,

        // Comparison reasoning (for audit trail, not user-facing)
        String comparisonReasoning,

        // Flags for special handling
        boolean requiresManualReview,
        String manualReviewReason
) {

    public record FieldMatchResult(
            String submittedValue,
            String extractedValue,
            boolean isMatch,
            double matchConfidence,
            String matchNote          // Explanation of near-match or mismatch
    ) {}

    public boolean isApproved() {
        return status == KycStatus.APPROVED;
    }

    public boolean isDefinitivelyRejected() {
        return status == KycStatus.REJECTED && !canRetry;
    }
}