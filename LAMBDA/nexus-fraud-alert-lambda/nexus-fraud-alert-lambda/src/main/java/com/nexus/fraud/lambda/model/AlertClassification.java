package com.nexus.fraud.lambda.model;

public record AlertClassification(
        AlertSeverity severity,
        String alertCategory,
        boolean requiresAccountAction,
        String recommendedAction
) {}

