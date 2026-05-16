package com.nexus.fraud.lambda.model;

import java.math.BigDecimal;

public record ComplianceAlertMessage(
        String alertId,
        String severity,
        String alertCategory,
        String transactionId,
        String userId,
        BigDecimal amount,
        String currency,
        BigDecimal riskScore,
        String triggeringFactorSummary,
        String reasoning,
        String recommendedAction,
        boolean sarRequired,
        String sarId,
        String regulatoryDeadline,
        String reviewUrl,
        String detectedAt
) {}
