package com.nexus.reporting.model;

import java.math.BigDecimal;

public record FraudAlertRecord(
    String alertId,
    String transactionId,
    String userId,
    BigDecimal amount,
    String currency,
    String severity,
    String alertCategory,
    BigDecimal riskScore,
    String recommendedAction,
    boolean requiresAccountAction,
    String processingStatus,
    String reviewStatus,
    boolean sarRequired,
    String sarId,
    String createdAt,
    String fraudDecisionJson
) {}
