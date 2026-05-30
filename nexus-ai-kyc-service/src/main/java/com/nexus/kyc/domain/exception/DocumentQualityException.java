package com.nexus.kyc.domain.exception;

import com.nexus.kyc.domain.model.enums.RejectionReason;

import java.util.List;

/**
 * Document Quality Exception — thrown when Stage 1 extraction
 * determines the document quality is insufficient for comparison.
 *
 * Pre-screening gates (before AI, saving API costs):
 * - Face detection: no face on passport/ID
 * - Face quality: brightness AND sharpness both below 50
 * - Text detection: fewer than 5 text elements
 * - Text length: total characters below 100
 *
 * Also thrown post-extraction when:
 * - overallConfidence < MINIMUM_CONFIDENCE_THRESHOLD (0.60)
 * - Document is expired (isExpired=true)
 *
 * Carries typed RejectionReason enums for structured audit trail.
 */
public class DocumentQualityException extends RuntimeException {

    private final List<RejectionReason> reasons;

    public DocumentQualityException(String message,
                                    List<RejectionReason> reasons) {
        super(message);
        this.reasons = reasons;
    }

    public List<RejectionReason> getReasons() {
        return reasons;
    }
}