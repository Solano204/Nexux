package com.nexus.analytics.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * UserMonthlyAnalytics — aggregated view of one user's month.
 * Built from Elasticsearch + Kafka Streams state stores.
 * Passed to the AI insight generator as context.
 */
public record UserMonthlyAnalytics(
        String userId,
        YearMonth period,
        BigDecimal totalSpending,
        BigDecimal previousPeriodSpending,
        BigDecimal totalIncome,
        BigDecimal previousPeriodIncome,
        double savingsRate,
        double previousSavingsRate,
        String currency,
        List<CategorySpending> spendingByCategory,
        List<Map<String, Object>> topMerchants,
        List<SpendingAnomaly> anomalies,
        Map<String, BigDecimal> spendingByDayOfWeek,
        double dataCompleteness       // 0.0-1.0 how complete this data is
) {
    public double spendingChangePercent() {
        if (previousPeriodSpending == null ||
                previousPeriodSpending.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return totalSpending.subtract(previousPeriodSpending)
                .doubleValue() /
                previousPeriodSpending.doubleValue() * 100;
    }
}