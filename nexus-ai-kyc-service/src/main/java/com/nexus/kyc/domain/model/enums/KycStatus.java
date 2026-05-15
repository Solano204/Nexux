package com.nexus.kyc.domain.model.enums;

public enum KycStatus {
    PENDING,            // Submitted, awaiting processing
    PROCESSING,         // AI pipeline running
    APPROVED,           // Identity verified
    REJECTED,           // Definitive rejection
    REVIEW_REQUIRED,    // Low confidence, needs human review
    FAILED,             // Technical failure (retry allowed)
    EXPIRED             // Document expired
}