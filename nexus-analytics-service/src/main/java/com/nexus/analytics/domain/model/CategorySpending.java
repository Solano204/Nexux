package com.nexus.analytics.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * CategorySpending — one category's spending summary within a period.
 * Used in UserMonthlyAnalytics.spendingByCategory list.
 * Passed to AI insight generator as context data.
 */
public record CategorySpending(
        String category,
        BigDecimal amount,
        int transactionCount,
        BigDecimal previousPeriodAmount,
        List<Map<String, Object>> topMerchants
) {
    public double changePercent() {
        if (previousPeriodAmount == null ||
                previousPeriodAmount.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return amount.subtract(previousPeriodAmount)
                .doubleValue() / previousPeriodAmount.doubleValue() * 100;
    }

    public List<Map<String, Object>> topMerchantsAsList() {
        return topMerchants != null ? topMerchants : List.of();
    }
}