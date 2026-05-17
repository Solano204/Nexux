package com.nexus.identity.domain.model.enums;

/**
 * KycDecision — possible outcomes of a KYC verification attempt.
 */
public enum KycDecision {
    /** Document submitted, analysis pending */
    PENDING,
    /** AI + Rekognition analysis approved the document */
    APPROVED,
    /** Document rejected — user may retry */
    REJECTED,
    /** Flagged for human compliance review */
    MANUAL_REVIEW,
    /** Lambda/AI service did not respond within SLA */
    TIMEOUT
}