package com.nexus.reporting.data;

import com.nexus.reporting.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Queries all DynamoDB tables for one report date.
 * Single entry point: queryAll(date) returns a DailyReportData.
 * Handles pagination, missing data, and deserialization.
 */
public class ReportDataQueryService {

    private static final Logger log =
        LoggerFactory.getLogger(ReportDataQueryService.class);

    private final DynamoDbClient dynamo;
    private final String transactionsTable;
    private final String fraudAlertsTable;
    private final String dailyStatsTable;
    private final String categoryStatsTable;
    private final String hourlyVolumeTable;
    private final String platformMetricsTable;

    public ReportDataQueryService(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
        this.transactionsTable = env("TRANSACTIONS_TABLE");
        this.fraudAlertsTable = env("FRAUD_ALERTS_TABLE");
        this.dailyStatsTable = env("ANALYTICS_DAILY_TABLE");
        this.categoryStatsTable = env("ANALYTICS_CATEGORY_TABLE");
        this.hourlyVolumeTable = env("ANALYTICS_HOURLY_VOLUME_TABLE");
        this.platformMetricsTable = env("PLATFORM_METRICS_TABLE");
    }

    public DailyReportData queryAll(LocalDate date) {
        log.info("Querying all report data for date={}", date);

        List<TransactionRecord> txns = queryTransactions(date);
        List<FraudAlertRecord> alerts = queryFraudAlerts(date);
        Map<String, DailyUserStats> userStats = queryDailyUserStats(date);
        Map<String, CategoryStats> catStats = queryCategoryStats(date);
        List<HourlyVolumeRecord> hourly = queryHourlyVolumes(date);
        PlatformMetrics platform = queryPlatformMetrics();

        BigDecimal totalVolume = txns.stream()
            .filter(t -> t.amount() != null)
            .map(TransactionRecord::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal fraudBlocked = alerts.stream()
            .filter(a -> a.amount() != null)
            .map(FraudAlertRecord::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Set<String> activeUsers = new HashSet<>();
        txns.forEach(t -> { if (t.userId() != null) activeUsers.add(t.userId()); });

        log.info("Data query complete: txns={} alerts={} users={}",
            txns.size(), alerts.size(), activeUsers.size());

        return new DailyReportData(date, txns, alerts, userStats, catStats,
            hourly, platform, txns.size(), alerts.size(),
            activeUsers.size(), totalVolume, fraudBlocked);
    }

    private List<TransactionRecord> queryTransactions(LocalDate date) {
        String dayStart = date + "T00:00:00Z";
        String dayEnd = date + "T23:59:59Z";
        List<TransactionRecord> results = new ArrayList<>();

        Map<String, AttributeValue> lastKey = null;
        do {
            ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(transactionsTable)
                .filterExpression("completedAt BETWEEN :s AND :e")
                .expressionAttributeValues(Map.of(
                    ":s", AttributeValue.fromS(dayStart),
                    ":e", AttributeValue.fromS(dayEnd)));

            if (lastKey != null) builder.exclusiveStartKey(lastKey);

            ScanResponse resp = dynamo.scan(builder.build());
            resp.items().forEach(item -> results.add(mapTransaction(item)));
            lastKey = resp.hasLastEvaluatedKey() ? resp.lastEvaluatedKey() : null;
        } while (lastKey != null);

        log.info("Queried {} transactions for {}", results.size(), date);
        return results;
    }

    private List<FraudAlertRecord> queryFraudAlerts(LocalDate date) {
        List<FraudAlertRecord> results = new ArrayList<>();
        try {
            QueryResponse resp = dynamo.query(QueryRequest.builder()
                .tableName(fraudAlertsTable)
                .indexName("DateIndex")
                .keyConditionExpression("GSI_DATE_PK = :pk")
                .expressionAttributeValues(Map.of(
                    ":pk", AttributeValue.fromS("DATE#" + date)))
                .build());

            resp.items().forEach(item -> results.add(mapFraudAlert(item)));
        } catch (Exception e) {
            log.warn("Failed to query fraud alerts: {}", e.getMessage());
        }
        return results;
    }

    private Map<String, DailyUserStats> queryDailyUserStats(LocalDate date) {
        Map<String, DailyUserStats> stats = new HashMap<>();
        try {
            ScanRequest req = ScanRequest.builder()
                .tableName(dailyStatsTable)
                .filterExpression("SK = :sk")
                .expressionAttributeValues(Map.of(
                    ":sk", AttributeValue.fromS("DATE#" + date)))
                .build();
            ScanResponse resp = dynamo.scan(req);
            resp.items().forEach(item -> {
                String uid = str(item, "userId");
                if (!uid.isEmpty()) {
                    stats.put(uid, new DailyUserStats(uid, date.toString(),
                        num(item, "totalSpent"), num(item, "totalReceived"),
                        numInt(item, "transactionCount"), numInt(item, "completedCount"),
                        numInt(item, "failedCount"), num(item, "maxTransactionAmount"),
                        str(item, "currency"), str(item, "lastTransactionAt")));
                }
            });
        } catch (Exception e) {
            log.warn("Failed to query daily user stats: {}", e.getMessage());
        }
        return stats;
    }

    private Map<String, CategoryStats> queryCategoryStats(LocalDate date) {
        // Category stats are monthly; query current month
        String yearMonth = date.toString().substring(0, 7);
        Map<String, CategoryStats> result = new HashMap<>();
        try {
            ScanRequest req = ScanRequest.builder()
                .tableName(categoryStatsTable)
                .filterExpression("contains(SK, :ym)")
                .expressionAttributeValues(Map.of(
                    ":ym", AttributeValue.fromS("MONTH#" + yearMonth)))
                .build();
            ScanResponse resp = dynamo.scan(req);
            resp.items().forEach(item -> {
                String uid = str(item, "userId");
                // Analytics aggregator embeds category in SK (MONTH#YYYY-MM#CAT#{category}),
                // not as a top-level attribute. Extract it from SK.
                String cat = str(item, "category");
                if (cat.isEmpty()) {
                    String sk = str(item, "SK");
                    int catIdx = sk.indexOf("#CAT#");
                    if (catIdx >= 0) cat = sk.substring(catIdx + 5);
                }
                if (!uid.isEmpty() && !cat.isEmpty()) {
                    result.computeIfAbsent(uid, k -> new CategoryStats(k, yearMonth, new HashMap<>()));
                    var detail = new CategoryStats.CategoryDetail(
                        str(item, "categoryName"), num(item, "totalAmount"),
                        numInt(item, "transactionCount"),
                        str(item, "topMerchantName"), num(item, "topMerchantAmount"));
                    ((HashMap<String, CategoryStats.CategoryDetail>) result.get(uid).categories()).put(cat, detail);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to query category stats: {}", e.getMessage());
        }
        return result;
    }

    private List<HourlyVolumeRecord> queryHourlyVolumes(LocalDate date) {
        List<HourlyVolumeRecord> volumes = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            String hourKey = String.format("%sT%02d", date, h);
            try {
                GetItemResponse resp = dynamo.getItem(GetItemRequest.builder()
                    .tableName(hourlyVolumeTable)
                    .key(Map.of(
                        "PK", AttributeValue.fromS("PLATFORM"),
                        "SK", AttributeValue.fromS("HOUR#" + hourKey)))
                    .build());
                if (resp.hasItem()) {
                    volumes.add(new HourlyVolumeRecord(h,
                        num(resp.item(), "totalVolume"),
                        numInt(resp.item(), "transactionCount")));
                } else {
                    volumes.add(HourlyVolumeRecord.zero(h));
                }
            } catch (Exception e) {
                volumes.add(HourlyVolumeRecord.zero(h));
            }
        }
        return volumes;
    }

    private PlatformMetrics queryPlatformMetrics() {
        try {
            GetItemResponse resp = dynamo.getItem(GetItemRequest.builder()
                .tableName(platformMetricsTable)
                .key(Map.of(
                    "PK", AttributeValue.fromS("PLATFORM"),
                    "SK", AttributeValue.fromS("REALTIME")))
                .build());
            if (resp.hasItem()) {
                var i = resp.item();
                int transactionsToday = numInt(i, "transactionsToday");
                BigDecimal volumeToday = num(i, "volumeToday");
                int activeUsersToday = numInt(i, "activeUsersToday");
                String updatedAt = str(i, "updatedAt");

                // peakTransactionsPerMinute: fall back to last-minute counter
                // (accurate peak tracking requires a windowing process)
                int peak = numInt(i, "peakTransactionsPerMinute");
                if (peak == 0) peak = numInt(i, "transactionsLastMinute");

                // avgFraudScore: aggregator accumulates sum + count; compute here
                BigDecimal totalFraudScoreSum = num(i, "totalFraudScoreSum");
                int fraudScoredCount = numInt(i, "fraudScoredCount");
                BigDecimal avgFraudScore = fraudScoredCount > 0
                    ? totalFraudScoreSum.divide(
                        BigDecimal.valueOf(fraudScoredCount), 4,
                        java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

                // fraudBlockRateToday: failedTransactionsToday / transactionsToday
                int failedToday = numInt(i, "failedTransactionsToday");
                BigDecimal fraudBlockRateToday = transactionsToday > 0
                    ? BigDecimal.valueOf(failedToday).divide(
                        BigDecimal.valueOf(transactionsToday), 4,
                        java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

                return new PlatformMetrics(transactionsToday, volumeToday, peak,
                    avgFraudScore, fraudBlockRateToday, activeUsersToday, updatedAt);
            }
        } catch (Exception e) {
            log.warn("Failed to query platform metrics: {}", e.getMessage());
        }
        return PlatformMetrics.empty();
    }

    private TransactionRecord mapTransaction(Map<String, AttributeValue> item) {
        return new TransactionRecord(
            str(item, "transactionId"), str(item, "userId"),
            str(item, "sourceAccountId"), str(item, "targetAccountId"),
            num(item, "amount"), str(item, "currency"),
            str(item, "transactionType"), str(item, "status"),
            str(item, "paymentNetwork"), str(item, "merchantId"),
            str(item, "merchantName"), str(item, "merchantCategoryCode"),
            str(item, "category"), num(item, "fraudScore"),
            str(item, "fraudDecision"), str(item, "createdAt"),
            str(item, "completedAt"), numLong(item, "processingDurationMs"),
            str(item, "sourceIp"), str(item, "description"));
    }

    private FraudAlertRecord mapFraudAlert(Map<String, AttributeValue> item) {
        return new FraudAlertRecord(
            str(item, "alertId"), str(item, "transactionId"),
            str(item, "userId"), num(item, "amount"), str(item, "currency"),
            str(item, "severity"), str(item, "alertCategory"),
            num(item, "riskScore"), str(item, "recommendedAction"),
            bool(item, "requiresAccountAction"), str(item, "processingStatus"),
            str(item, "reviewStatus"), bool(item, "sarRequired"),
            str(item, "sarId"), str(item, "createdAt"),
            str(item, "fraudDecision"));
    }

    // ── DynamoDB attribute helpers ────────────────────────
    private String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.s() != null ? v.s() : "";
    }
    private BigDecimal num(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        if (v != null && v.n() != null) {
            try { return new BigDecimal(v.n()); } catch (Exception e) { /* fall through */ }
        }
        return BigDecimal.ZERO;
    }
    private int numInt(Map<String, AttributeValue> item, String key) {
        return num(item, key).intValue();
    }
    private Long numLong(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        if (v != null && v.n() != null) {
            try { return Long.parseLong(v.n()); } catch (Exception e) { /* fall through */ }
        }
        return null;
    }
    private boolean bool(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.bool() != null && v.bool();
    }
    private String env(String key) {
        return System.getenv(key) != null ? System.getenv(key) : key.toLowerCase().replace('_', '-');
    }
}
