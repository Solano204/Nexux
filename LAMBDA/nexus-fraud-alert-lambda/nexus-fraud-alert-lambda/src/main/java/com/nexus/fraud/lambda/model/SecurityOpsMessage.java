package com.nexus.fraud.lambda.model;


import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SecurityOpsMessage(
        String alertId,
        String transactionId,
        String userId,
        BigDecimal riskScore,
        String severity,
        String alertCategory,
        String recommendedAction,
        Map<String, String> toolCallSummary,
        List<String> triggeringFactors,
        String traceId,
        String timestamp
) {}