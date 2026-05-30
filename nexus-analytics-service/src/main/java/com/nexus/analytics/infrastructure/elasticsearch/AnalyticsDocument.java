package com.nexus.analytics.infrastructure.elasticsearch;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Analytics Document — Elasticsearch index document.
 *
 * Index pattern: nexus-analytics-user-{year}-{month}
 * Routing: userId (co-locates all docs for one user on same shard).
 *
 * Contains pre-computed monthly analytics:
 * spending by category, income, savings rate, top merchants,
 * anomalies detected, day-of-week/hour-of-day breakdowns.
 */
@Document(indexName = "nexus-analytics-user")
@Setting(shards = 2, replicas = 1)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyticsDocument {

    @Id
    private String documentId;

    @Field(type = FieldType.Keyword)
    private String userId;

    @Field(type = FieldType.Keyword)
    private String periodType;        // DAILY, WEEKLY, MONTHLY

    @Field(type = FieldType.Integer)
    private int year;

    @Field(type = FieldType.Integer)
    private int month;

    @Field(type = FieldType.Integer)
    private int week;

    @Field(type = FieldType.Date)
    private Instant startDate;

    @Field(type = FieldType.Date)
    private Instant endDate;

    // Spending
    @Field(type = FieldType.Double)
    private BigDecimal totalSpending;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Nested)
    private List<CategoryBreakdown> spendingByCategory;

    // Income
    @Field(type = FieldType.Double)
    private BigDecimal totalIncome;

    @Field(type = FieldType.Float)
    private double savingsRate;

    // Top merchants
    @Field(type = FieldType.Nested)
    private List<MerchantEntry> topMerchants;

    // Anomalies
    @Field(type = FieldType.Nested)
    private List<AnomalyEntry> anomalies;

    @Field(type = FieldType.Float)
    private double dataCompleteness;

    @Field(type = FieldType.Date)
    private Instant computedAt;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CategoryBreakdown {
        private String category;
        private BigDecimal amount;
        private int transactionCount;
        private double percentOfTotal;
        private List<MerchantEntry> topMerchants;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MerchantEntry {
        private String name;
        private BigDecimal amount;
        private int count;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AnomalyEntry {
        private String type;
        private String category;
        private String severity;
        private double percentageChange;
        private Instant detectedAt;
    }
}