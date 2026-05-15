package com.nexus.analytics.application.anomaly;

import com.nexus.analytics.domain.model.*;
import com.nexus.analytics.domain.model.enums.AnomalyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Spending Anomaly Detector — Pure statistics, no AI.
 *
 * Z-score based: (current - mean) / stdDev.
 * Threshold: |z| > 2.0 = anomaly.
 *
 * Why stats before AI:
 * - Z-score is faster (microseconds vs seconds)
 * - Z-score is cheaper (no API cost)
 * - Z-score is auditable (exact formula, reproducible)
 * - AI interprets anomalies detected by statistics
 *
 * The AI explains WHY; the stats detect WHETHER.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpendingAnomalyDetector {

    private static final double ANOMALY_Z_THRESHOLD = 2.0;
    private static final int MIN_SAMPLES_FOR_DETECTION = 3;

    /**
     * Detects anomalies in current period vs historical statistics.
     */
    public List<SpendingAnomaly> detect(
            UserMonthlyAnalytics current,
            List<CategorySpending> historicalBaseline) {

        List<SpendingAnomaly> anomalies = new ArrayList<>();

        for (CategorySpending currentCat :
                current.spendingByCategory()) {

            String category = currentCat.category();

            // Find historical stats for this category
            OptionalDouble historicalMean = historicalBaseline
                    .stream()
                    .filter(h -> h.category().equals(category))
                    .mapToDouble(h -> h.amount().doubleValue())
                    .average();

            if (historicalMean.isEmpty()) continue;

            double mean = historicalMean.getAsDouble();
            if (mean <= 0) continue;

            // Need variance for Z-score
            double[] historicalValues = historicalBaseline.stream()
                    .filter(h -> h.category().equals(category))
                    .mapToDouble(h -> h.amount().doubleValue())
                    .toArray();

            if (historicalValues.length < MIN_SAMPLES_FOR_DETECTION)
                continue;

            double variance = Arrays.stream(historicalValues)
                    .map(v -> Math.pow(v - mean, 2))
                    .average().orElse(0);

            double stdDev = Math.sqrt(variance);
            if (stdDev <= 0) continue;

            double current_value = currentCat.amount().doubleValue();
            double zScore = (current_value - mean) / stdDev;

            if (Math.abs(zScore) > ANOMALY_Z_THRESHOLD) {
                double pctChange =
                        ((current_value - mean) / mean) * 100;

                SpendingAnomaly anomaly = SpendingAnomaly.builder()
                        .anomalyId(UUID.randomUUID().toString())
                        .userId(current.userId())
                        .type(zScore > 0
                                ? AnomalyType.SPIKE
                                : AnomalyType.DROP)
                        .category(category)
                        .severity(classifySeverity(
                                Math.abs(zScore)))
                        .zScore(zScore)
                        .percentageChange(pctChange)
                        .absoluteChange(BigDecimal.valueOf(
                                current_value - mean))
                        .historicalMean(BigDecimal.valueOf(mean))
                        .currentValue(currentCat.amount())
                        .currency(current.currency())
                        .topContributingMerchants(
                                currentCat.topMerchantsAsList())
                        .detectedAt(Instant.now())
                        .reportedToUser(false)
                        .build();

                anomalies.add(anomaly);

                log.info("Anomaly detected: userId={} " +
                                "category={} pctChange={:.1f}% z={:.2f}",
                        current.userId(), category,
                        pctChange, zScore);
            }
        }

        // Savings rate turned negative
        if (current.savingsRate() < 0 &&
                current.previousSavingsRate() >= 0) {

            anomalies.add(SpendingAnomaly.builder()
                    .anomalyId(UUID.randomUUID().toString())
                    .userId(current.userId())
                    .type(AnomalyType.NEGATIVE_SAVINGS)
                    .category("ALL")
                    .severity("HIGH")
                    .zScore(0)
                    .percentageChange(100)
                    .absoluteChange(current.totalSpending()
                            .subtract(current.totalIncome()))
                    .historicalMean(current.totalIncome())
                    .currentValue(current.totalSpending())
                    .currency(current.currency())
                    .topContributingMerchants(List.of())
                    .detectedAt(Instant.now())
                    .reportedToUser(false)
                    .build());
        }

        return anomalies;
    }

    private String classifySeverity(double absoluteZScore) {
        if (absoluteZScore > 4.0) return "HIGH";
        if (absoluteZScore > 3.0) return "MEDIUM";
        return "LOW";
    }
}