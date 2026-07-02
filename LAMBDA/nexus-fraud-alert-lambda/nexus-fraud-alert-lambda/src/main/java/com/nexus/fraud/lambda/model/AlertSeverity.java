package com.nexus.fraud.lambda.model;


import java.math.BigDecimal;

public enum AlertSeverity {
    ELEVATED,  // 85-89: high risk, monitor
    HIGH,      // 90-94: serious, compliance notification
    CRITICAL;  // 95-100: active attack suspected, emergency response

    public static AlertSeverity fromScore(BigDecimal score) {
        if (score.compareTo(BigDecimal.valueOf(95)) >= 0)
            return CRITICAL;
        if (score.compareTo(BigDecimal.valueOf(90)) >= 0)
            return HIGH;
        return ELEVATED;
    }
}

