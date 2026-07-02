package com.nexus.reporting.data;

import com.nexus.reporting.model.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Consolidated data queried from all DynamoDB tables for one report day.
 * Built once by ReportDataQueryService, consumed by all report generators.
 */
public record DailyReportData(
    LocalDate reportDate,
    List<TransactionRecord> transactions,
    List<FraudAlertRecord> fraudAlerts,
    Map<String, DailyUserStats> userDailyStats,
    Map<String, CategoryStats> categoryStats,
    List<HourlyVolumeRecord> hourlyVolumes,
    PlatformMetrics platformMetrics,
    int transactionCount,
    int fraudAlertCount,
    int activeUserCount,
    BigDecimal totalTransactionVolume,
    BigDecimal totalFraudBlockedVolume
) {
    public double successRate() {
        if (transactionCount == 0) return 1.0;
        long completed = transactions.stream()
            .filter(t -> "COMPLETED".equals(t.status()))
            .count();
        return (double) completed / transactionCount;
    }

    public static DailyReportData empty(LocalDate date) {
        return new DailyReportData(date, List.of(), List.of(),
            Map.of(), Map.of(), List.of(),
            PlatformMetrics.empty(), 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
