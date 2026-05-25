package com.nexus.risk.domain.model.enums;

public enum RiskTier {
    VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH;

    public static RiskTier fromScore(int score) {
        if (score <= 20) return VERY_LOW;
        if (score <= 40) return LOW;
        if (score <= 60) return MEDIUM;
        if (score <= 80) return HIGH;
        return VERY_HIGH;
    }
}