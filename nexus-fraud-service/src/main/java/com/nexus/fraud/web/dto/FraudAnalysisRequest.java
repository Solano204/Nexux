package com.nexus.fraud.web.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Input to the fraud analysis pipeline.
 * Populated from CheckFraudCommand Kafka payload.
 */
public record FraudAnalysisRequest(
        String transactionId,
        String sagaId,
        String userId,
        String sourceAccountId,
        String targetAccountId,
        BigDecimal amount,
        String currency,
        String transactionType,
        String description,
        String merchantId,
        String merchantName,
        String merchantCategoryCode,
        String sourceIp,
        String deviceFingerprint,
        boolean isKnownDevice,
        int accountAgeDays,
        int totalTransactionCount,
        Map<String, Object> preComputedSignals,
        String traceId
) {}