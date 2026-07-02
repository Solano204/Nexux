package com.nexus.reporting.model;

import java.math.BigDecimal;

public record PlatformMetrics(
    int transactionsToday,
    BigDecimal volumeToday,
    int peakTransactionsPerMinute,
    BigDecimal avgFraudScore,
    BigDecimal fraudBlockRateToday,
    int activeUsersToday,
    String updatedAt
) {
    public static PlatformMetrics empty() {
        return new PlatformMetrics(0, BigDecimal.ZERO, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
    }
}
