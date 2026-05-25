package com.nexus.kyc.domain.model;

import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.RejectionReason;

import java.util.List;

/**
 * KycExtractedData — Structured output from Stage 1 AI (Section 3).
 *
 * GPT-4.1-mini vision reads the document image and populates
 * every field it can read. Fields it cannot read are null.
 *
 * Quality indicators:
 * - overallConfidence: 0.0-1.0. Below 0.6 → reject before Stage 2
 * - imageQualityIssues: specific problems the model detected
 * - isDocumentExpired: checked against extractedExpiryDate
 * - isForgeryIndicatorPresent: document security features assessment
 *
 * All name/date fields are as read from the document — not normalized.
 * Normalization happens in Stage 2 comparison.
 */
public record KycExtractedData(

        // Document classification
        DocumentType detectedDocumentType,
        String issuingCountry,
        String documentNumber,

        // Personal data extracted from document
        String extractedFullName,
        String extractedFirstName,
        String extractedLastName,
        String extractedDateOfBirth,   // As printed on document: DD/MM/YYYY
        String extractedExpiryDate,
        String extractedNationality,
        String extractedGender,
        String extractedAddress,       // For driving licenses

        // MRZ (Machine Readable Zone) if present
        String mrzLine1,
        String mrzLine2,
        boolean mrzPresent,
        boolean mrzConsistentWithVisualZone,

        // Quality assessment
        double overallConfidence,       // 0.0-1.0
        double textReadabilityScore,    // 0.0-1.0 clarity of printed text
        double photoQualityScore,       // 0.0-1.0 ID photo quality
        List<String> imageQualityIssues,// "BLURRY", "GLARE", "PARTIAL", etc.

        // Validity indicators
        boolean isDocumentExpired,
        boolean isForgeryIndicatorPresent,
        String forgeryIndicatorDetail,  // Specific concern if present

        // What the model could NOT read (for partial extractions)
        List<String> unreadableFields,

        // Reasoning (for audit trail)
        String extractionReasoning      // Brief explanation of confidence
) {

    /** Below this threshold, reject without running Stage 2 */
    public static final double MINIMUM_CONFIDENCE_THRESHOLD = 0.60;

    /** Below this, flag for human review even if data matches */
    public static final double REVIEW_CONFIDENCE_THRESHOLD = 0.75;

    public boolean isSufficientQualityForComparison() {
        return overallConfidence >= MINIMUM_CONFIDENCE_THRESHOLD
                && !isForgeryIndicatorPresent
                && !isDocumentExpired;
    }

    public boolean requiresHumanReview() {
        return overallConfidence < REVIEW_CONFIDENCE_THRESHOLD
                || isForgeryIndicatorPresent
                || (mrzPresent && !mrzConsistentWithVisualZone);
    }
}