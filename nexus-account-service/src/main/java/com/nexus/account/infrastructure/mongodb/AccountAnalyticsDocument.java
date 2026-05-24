package com.nexus.account.infrastructure.mongodb;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AccountAnalyticsDocument — MongoDB pre-aggregated analytics.
 *
 * Stores pre-computed spending patterns, category breakdowns,
 * and trend data per account. Updated periodically by analytics
 * aggregation jobs, NOT on every transaction.
 *
 * This is the CQRS read-side analytics projection — optimized
 * for fast single-document reads from the Account Advisor AI
 * and the analytics dashboard endpoints.
 *
 * Collection: account_analytics
 * Indexes: accountId (unique), userId
 */
@Document(collection = "account_analytics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AccountAnalyticsDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String accountId;

    @Indexed
    private String userId;

    /**
     * Current billing period analytics (this month).
     */
    private PeriodAnalytics currentPeriod;

    /**
     * Previous billing period (last month) — for comparison.
     */
    private PeriodAnalytics previousPeriod;

    /**
     * Monthly trend data — last 12 months of spending/income.
     */
    @Builder.Default
    private List<MonthlyTrend> monthlyTrends = new ArrayList<>();

    /**
     * AI-generated savings opportunities from last advisor run.
     */
    @Builder.Default
    private List<SavingsOpportunity> savingsOpportunities = new ArrayList<>();

    /**
     * Top recurring transactions (subscriptions, bills).
     */
    @Builder.Default
    private List<RecurringTransaction> recurringTransactions = new ArrayList<>();

    /**
     * Risk indicators from the risk-scoring service.
     */
    private RiskIndicators riskIndicators;

    private Instant lastUpdated;
    private Instant lastAdvisorRun;

    // ══════════════════════════════════════════════════════════
    // EMBEDDED DOCUMENTS
    // ══════════════════════════════════════════════════════════

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PeriodAnalytics {
        private String periodLabel;       // "2026-05"
        private BigDecimal totalSpent;
        private BigDecimal totalReceived;
        private BigDecimal netFlow;       // received - spent
        private Integer transactionCount;
        private BigDecimal averageTransactionAmount;
        private BigDecimal largestTransaction;
        private String largestTransactionCategory;

        /**
         * Spending breakdown by category.
         * Keys: "food", "transport", "utilities", "entertainment", etc.
         */
        @Builder.Default
        private Map<String, BigDecimal> spendingByCategory = new HashMap<>();

        /**
         * Day-of-week spending pattern (0=Monday..6=Sunday).
         */
        @Builder.Default
        private Map<Integer, BigDecimal> spendingByDayOfWeek = new HashMap<>();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MonthlyTrend {
        private String month;             // "2026-05"
        private BigDecimal totalSpent;
        private BigDecimal totalReceived;
        private BigDecimal netFlow;
        private Integer transactionCount;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SavingsOpportunity {
        private String category;
        private BigDecimal currentMonthlySpend;
        private BigDecimal suggestedTarget;
        private BigDecimal potentialSaving;
        private String recommendation;
        private Instant generatedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RecurringTransaction {
        private String description;
        private BigDecimal amount;
        private String frequency;         // "MONTHLY", "WEEKLY", "BIWEEKLY"
        private String category;
        private Integer dayOfMonth;       // for monthly recurring
        private Instant lastOccurrence;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RiskIndicators {
        private String riskLevel;         // "LOW", "MEDIUM", "HIGH"
        private Double riskScore;         // 0.0 - 1.0
        private Integer unusualTransactionCount;
        private Boolean highVelocityFlag;
        private Instant lastAssessedAt;
    }
}