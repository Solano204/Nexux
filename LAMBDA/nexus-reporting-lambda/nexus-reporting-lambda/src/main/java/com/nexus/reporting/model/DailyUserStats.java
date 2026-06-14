package com.nexus.reporting.model;

import java.math.BigDecimal;

public record DailyUserStats(
    String userId,
    String date,
    BigDecimal totalSpent,
    BigDecimal totalReceived,
    int transactionCount,
    int completedCount,
    int failedCount,
    BigDecimal maxTransactionAmount,
    String currency,
    String lastTransactionAt
) {
    public static DailyUserStats empty(String userId, String date) {
        return new DailyUserStats(userId, date,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0,
            BigDecimal.ZERO, "MXN", null);
    }
}
