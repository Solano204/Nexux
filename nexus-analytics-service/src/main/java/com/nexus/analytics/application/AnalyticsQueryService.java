package com.nexus.analytics.application;

import com.nexus.analytics.domain.model.*;
import com.nexus.analytics.infrastructure.elasticsearch.AnalyticsDocument;
import com.nexus.analytics.infrastructure.elasticsearch.AnalyticsDocumentRepository;
import com.nexus.analytics.infrastructure.redis.AnalyticsRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

/**
 * Analytics Query Service — reads pre-computed analytics.
 *
 * Data sources:
 * 1. Elasticsearch: monthly/weekly analytics documents
 * 2. Redis: cached results, merchant leaderboards
 * 3. Kafka Streams state stores (via InternalAnalyticsController)
 *
 * Structured Concurrency for parallel queries when
 * gathering data for AI insight generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsQueryService {

    private final AnalyticsDocumentRepository esRepository;
    private final AnalyticsRedisRepository redisRepository;

    public UserMonthlyAnalytics getMonthlyAnalytics(
            String userId, YearMonth month) {

        return esRepository.findByUserIdAndPeriodTypeAndYearAndMonth(
                        userId, "MONTHLY", month.getYear(), month.getMonthValue())
                .map(doc -> mapToMonthlyAnalytics(userId, month, doc))
                .orElse(emptyAnalytics(userId, month));
    }

    public UserMonthlyAnalytics getMonthlyAnalyticsSafe(
            String userId, YearMonth month) {
        try {
            return getMonthlyAnalytics(userId, month);
        } catch (Exception e) {
            log.warn("Failed to get analytics for userId={}: {}",
                    userId, e.getMessage());
            return emptyAnalytics(userId, month);
        }
    }

    public List<SpendingAnomaly> getAnomalies(
            String userId, YearMonth month) {
        // In production: query nexus-analytics-anomalies ES index
        return List.of();
    }

    public Object getSpendingTrend(String userId, YearMonth current) {
        try (var scope = StructuredTaskScope.open()) {
            var currentF = scope.fork(() -> getMonthlyAnalytics(userId, current));
            var previousF = scope.fork(() -> getMonthlyAnalytics(userId, current.minusMonths(1)));
            scope.join();

            var curr = currentF.get();
            var prev = previousF.get();

            return Map.of(
                    "currentMonth", Map.of(
                            "period", current.toString(),
                            "totalSpending", curr.totalSpending(),
                            "savingsRate", curr.savingsRate()),
                    "previousMonth", Map.of(
                            "period", current.minusMonths(1).toString(),
                            "totalSpending", prev.totalSpending(),
                            "savingsRate", prev.savingsRate()),
                    "spendingChangePercent", curr.spendingChangePercent()
            );
        } catch (Exception e) {
            log.warn("Trend computation failed: {}", e.getMessage());
            return Map.of("error", "TREND_UNAVAILABLE");
        }
    }

    public UserMonthlyAnalytics mergeAnalytics(
            String userId, YearMonth month,
            UserMonthlyAnalytics current,
            UserMonthlyAnalytics previous,
            List<SpendingAnomaly> anomalies) {

        return new UserMonthlyAnalytics(
                userId, month,
                current.totalSpending(),
                previous.totalSpending(),
                current.totalIncome(),
                previous.totalIncome(),
                current.savingsRate(),
                previous.savingsRate(),
                current.currency(),
                current.spendingByCategory(),
                current.topMerchants(),
                anomalies,
                current.spendingByDayOfWeek(),
                current.dataCompleteness()
        );
    }

    private UserMonthlyAnalytics mapToMonthlyAnalytics(
            String userId, YearMonth month, AnalyticsDocument doc) {

        List<CategorySpending> categories = doc.getSpendingByCategory() != null
                ? doc.getSpendingByCategory().stream()
                .map(c -> new CategorySpending(
                        c.getCategory(),
                        c.getAmount(),
                        c.getTransactionCount(),
                        null,
                        c.getTopMerchants() != null
                                ? c.getTopMerchants().stream()
                                .map(m -> Map.<String, Object>of(
                                        "name", m.getName(),
                                        "amount", m.getAmount(),
                                        "count", m.getCount()))
                                .collect(Collectors.toList())
                                : List.of()))
                .collect(Collectors.toList())
                : List.of();

        List<Map<String, Object>> topMerchants = doc.getTopMerchants() != null
                ? doc.getTopMerchants().stream()
                .map(m -> Map.<String, Object>of(
                        "name", m.getName(),
                        "amount", m.getAmount(),
                        "count", m.getCount()))
                .collect(Collectors.toList())
                : List.of();

        return new UserMonthlyAnalytics(
                userId, month,
                doc.getTotalSpending() != null ? doc.getTotalSpending() : BigDecimal.ZERO,
                BigDecimal.ZERO,
                doc.getTotalIncome() != null ? doc.getTotalIncome() : BigDecimal.ZERO,
                BigDecimal.ZERO,
                doc.getSavingsRate(),
                0.0,
                doc.getCurrency() != null ? doc.getCurrency() : "MXN",
                categories,
                topMerchants,
                List.of(),
                Map.of(),
                doc.getDataCompleteness()
        );
    }

    private UserMonthlyAnalytics emptyAnalytics(
            String userId, YearMonth month) {
        return new UserMonthlyAnalytics(
                userId, month,
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                0.0, 0.0, "MXN",
                List.of(), List.of(), List.of(), Map.of(), 0.0);
    }
}