package com.nexus.fraud.domain.model.enums;

public enum RecommendedAction {
    PROCEED_NORMALLY,
    NOTIFY_USER,
    REQUIRE_ADDITIONAL_AUTH,
    ESCALATE_TO_COMPLIANCE,
    BLOCK_ACCOUNT_TEMPORARY,
    BLOCK_ACCOUNT_PERMANENT,
    FILE_SAR  // Suspicious Activity Report — regulatory requirement
}