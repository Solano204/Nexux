package com.nexus.analytics.unit;

import com.nexus.analytics.application.anomaly.SpendingAnomalyDetector;
import com.nexus.analytics.domain.model.CategorySpending;
import com.nexus.analytics.domain.model.SpendingAnomaly;
import com.nexus.analytics.domain.model.UserMonthlyAnalytics;
import com.nexus.analytics.domain.model.enums.AnomalyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {

    private final SpendingAnomalyDetector detector = new SpendingAnomalyDetector();

    private CategorySpending historicalEntry(String category, double amount) {
        return new CategorySpending(category, BigDecimal.valueOf(amount), 5, null, List.of());
    }

    private UserMonthlyAnalytics analytics(String userId, List<CategorySpending> current,
                                            double savingsRate, double previousSavingsRate,
                                            BigDecimal totalSpending, BigDecimal totalIncome) {
        return new UserMonthlyAnalytics(userId, YearMonth.now(), totalSpending, totalSpending,
                totalIncome, totalIncome, savingsRate, previousSavingsRate, "MXN",
                current, List.of(), List.of(), Map.of(), 1.0);
    }

    @Test
    void detectsSpikeWhenCurrentSpendingFarAboveHistoricalMean() {
        // Historical GROCERIES: ~1000 +/- small variance
        List<CategorySpending> historical = List.of(
                historicalEntry("GROCERIES", 950), historicalEntry("GROCERIES", 1000),
                historicalEntry("GROCERIES", 1050), historicalEntry("GROCERIES", 980),
                historicalEntry("GROCERIES", 1020));
        CategorySpending current = new CategorySpending("GROCERIES", new BigDecimal("5000"), 20, null, List.of());
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(current), 0.2, 0.2,
                new BigDecimal("5000"), new BigDecimal("10000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, historical);

        assertThat(anomalies).anySatisfy(a -> {
            assertThat(a.getType()).isEqualTo(AnomalyType.SPIKE);
            assertThat(a.getCategory()).isEqualTo("GROCERIES");
            assertThat(a.getZScore()).isGreaterThan(2.0);
        });
    }

    @Test
    void detectsDropWhenCurrentSpendingFarBelowHistoricalMean() {
        List<CategorySpending> historical = List.of(
                historicalEntry("DINING", 900), historicalEntry("DINING", 1000),
                historicalEntry("DINING", 1100), historicalEntry("DINING", 950));
        CategorySpending current = new CategorySpending("DINING", new BigDecimal("10"), 1, null, List.of());
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(current), 0.5, 0.5,
                new BigDecimal("10"), new BigDecimal("5000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, historical);

        assertThat(anomalies).anySatisfy(a -> {
            assertThat(a.getType()).isEqualTo(AnomalyType.DROP);
            assertThat(a.getZScore()).isLessThan(-2.0);
        });
    }

    @Test
    void noAnomalyWhenSpendingWithinNormalRange() {
        List<CategorySpending> historical = List.of(
                historicalEntry("UTILITIES", 480), historicalEntry("UTILITIES", 500),
                historicalEntry("UTILITIES", 520), historicalEntry("UTILITIES", 510));
        CategorySpending current = new CategorySpending("UTILITIES", new BigDecimal("505"), 3, null, List.of());
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(current), 0.3, 0.3,
                new BigDecimal("505"), new BigDecimal("5000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, historical);

        assertThat(anomalies).isEmpty();
    }

    @Test
    void skipsCategoryWithNoHistoricalBaseline() {
        CategorySpending current = new CategorySpending("NEW_CATEGORY", new BigDecimal("10000"), 1, null, List.of());
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(current), 0.2, 0.2,
                new BigDecimal("10000"), new BigDecimal("20000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, List.of());

        assertThat(anomalies).isEmpty();
    }

    @Test
    void skipsCategoryWithFewerThanThreeHistoricalSamples() {
        List<CategorySpending> historical = List.of(
                historicalEntry("RARE_CAT", 100), historicalEntry("RARE_CAT", 200));
        CategorySpending current = new CategorySpending("RARE_CAT", new BigDecimal("10000"), 1, null, List.of());
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(current), 0.2, 0.2,
                new BigDecimal("10000"), new BigDecimal("20000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, historical);

        assertThat(anomalies).isEmpty();
    }

    @Test
    void flagsNegativeSavingsTransition() {
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(), -0.1, 0.15,
                new BigDecimal("6000"), new BigDecimal("5000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, List.of());

        assertThat(anomalies).anySatisfy(a -> assertThat(a.getType()).isEqualTo(AnomalyType.NEGATIVE_SAVINGS));
    }

    @Test
    void doesNotFlagNegativeSavingsWhenAlreadyNegativeLastPeriod() {
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(), -0.1, -0.05,
                new BigDecimal("6000"), new BigDecimal("5000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, List.of());

        assertThat(anomalies).noneMatch(a -> a.getType() == AnomalyType.NEGATIVE_SAVINGS);
    }

    @Test
    void classifiesSeverityByZScoreMagnitude() {
        // Construct a very tight historical distribution so a moderate jump produces a huge Z-score
        List<CategorySpending> historical = List.of(
                historicalEntry("RENT", 1000), historicalEntry("RENT", 1000),
                historicalEntry("RENT", 1000), historicalEntry("RENT", 1001),
                historicalEntry("RENT", 999));
        CategorySpending current = new CategorySpending("RENT", new BigDecimal("50000"), 1, null, List.of());
        UserMonthlyAnalytics analytics = analytics("user-1", List.of(current), 0.2, 0.2,
                new BigDecimal("50000"), new BigDecimal("60000"));

        List<SpendingAnomaly> anomalies = detector.detect(analytics, historical);

        assertThat(anomalies).anySatisfy(a -> assertThat(a.getSeverity()).isEqualTo("HIGH"));
    }
}
