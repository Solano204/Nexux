package com.nexus.kyc.domain.model.enums;


public enum RejectionReason {
    // Document quality issues
    IMAGE_BLURRY,
    IMAGE_TOO_DARK,
    IMAGE_PARTIAL,
    DOCUMENT_GLARE,

    // Document validity issues
    DOCUMENT_EXPIRED,
    DOCUMENT_DAMAGED,
    DOCUMENT_NOT_SUPPORTED,
    DOCUMENT_UNREADABLE,

    // Data mismatch issues
    NAME_MISMATCH,
    DOB_MISMATCH,
    DOCUMENT_NUMBER_MISMATCH,
    NATIONALITY_MISMATCH,

    // Fraud indicators
    SUSPECTED_FORGERY,
    SUSPECTED_MANIPULATION,
    DUPLICATE_IDENTITY,

    // Technical
    AI_EXTRACTION_FAILED,
    LOW_CONFIDENCE
}
