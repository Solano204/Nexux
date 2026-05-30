package com.nexus.analytics.application;

import com.nexus.analytics.domain.model.*;
import com.nexus.analytics.domain.model.enums.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

/**
 * Statistical Insight Generator — Template-based fallback.
 *
 * Used when:
 * 1. OpenAI API unavailable after retries
 * 2. AI returns unparseable JSON
 * 3. AI insight has relevance score < 0.5
 *
 * Produces accurate, numerical insights without AI interpretation.
 * Less nuanced but always works.
 * Same FinancialInsight record structure — UI renders identically.
 */
@Component
public class StatisticalInsightGenerator {

    public List<FinancialInsight> generate(
            UserMonthlyAnalytics analytics, String language) {

        List<FinancialInsight> insights = new ArrayList<>();
        boolean es = "es".equals(language);

        // Insight 1: Overall spending trend
        double change = analytics.spendingChangePercent();
        if (Math.abs(change) > 5) {
            String direction = change > 0
                ? (es ? "aumentó" : "increased")
                : (es ? "disminuyó" : "decreased");

            String title = es
                ? String.format("Tu gasto %s %.0f%% este mes",
                    direction, Math.abs(change))
                : String.format("Your spending %s %.0f%% this month",
                    direction, Math.abs(change));

            String narrative = es
                ? String.format(
                    "Gastaste %s %s en %s, comparado con %s %s el mes anterior.",
                    analytics.currency(),
                    formatAmount(analytics.totalSpending()),
                    analytics.period(),
                    analytics.currency(),
                    formatAmount(analytics.previousPeriodSpending()))
                : String.format(
                    "You spent %s %s in %s, compared to %s %s last month.",
                    analytics.currency(),
                    formatAmount(analytics.totalSpending()),
                    analytics.period(),
                    analytics.currency(),
                    formatAmount(analytics.previousPeriodSpending()));

            insights.add(new FinancialInsight(
                UUID.randomUUID().toString(),
                InsightCategory.SPENDING_TREND,
                title, narrative,
                List.of(
                    String.format("%s %.2f total",
                        analytics.currency(),
                        analytics.totalSpending()),
                    String.format("%.1f%% change", change)),
                null,
                Math.min(0.8, Math.abs(change) / 100.0 * 2),
                change > 0
                    ? InsightSeverity.WARNING
                    : InsightSeverity.POSITIVE,
                language, analytics.period(),
                "STATISTICAL", Instant.now(), Map.of()
            ));
        }

        // Insight 2: Top category
        analytics.spendingByCategory().stream()
            .max(Comparator.comparing(CategorySpending::amount))
            .ifPresent(top -> {
                double pct = analytics.totalSpending()
                    .compareTo(BigDecimal.ZERO) > 0
                    ? top.amount().doubleValue() /
                      analytics.totalSpending().doubleValue() * 100
                    : 0;

                String title = es
                    ? String.format("%s fue tu mayor categoría (%.0f%%)",
                        categoryName(top.category(), es), pct)
                    : String.format("%s was your top category (%.0f%%)",
                        categoryName(top.category(), es), pct);

                insights.add(new FinancialInsight(
                    UUID.randomUUID().toString(),
                    InsightCategory.CATEGORY_SPOTLIGHT,
                    title,
                    String.format(
                        es ? "Gastaste %s %.2f en %s (%d transacciones)."
                           : "You spent %s %.2f on %s (%d transactions).",
                        analytics.currency(),
                        top.amount().doubleValue(),
                        categoryName(top.category(), es),
                        top.transactionCount()),
                    List.of(
                        String.format("%s %.2f",
                            analytics.currency(),
                            top.amount().doubleValue()),
                        String.format("%d transactions",
                            top.transactionCount())),
                    null, 0.70, InsightSeverity.INFO,
                    language, analytics.period(),
                    "STATISTICAL", Instant.now(), Map.of()
                ));
            });

        return insights;
    }

    public FinancialInsight generateAnomalyInsight(
            SpendingAnomaly anomaly, String language) {

        boolean es = "es".equals(language);

        String title = es
            ? String.format("Gasto inusual en %s (+%.0f%%)",
                anomaly.getCategory(),
                anomaly.getPercentageChange())
            : String.format("Unusual spending in %s (+%.0f%%)",
                anomaly.getCategory(),
                anomaly.getPercentageChange());

        String narrative = es
            ? String.format(
                "Tu gasto en %s fue %.0f%% mayor que tu promedio histórico " +
                "de %s %.2f. Esto representa %s %.2f adicionales este período.",
                anomaly.getCategory(),
                anomaly.getPercentageChange(),
                anomaly.getCurrency(),
                anomaly.getHistoricalMean().doubleValue(),
                anomaly.getCurrency(),
                anomaly.getAbsoluteChange().doubleValue())
            : String.format(
                "Your %s spending was %.0f%% above your historical average " +
                "of %s %.2f — %s %.2f more than usual this period.",
                anomaly.getCategory(),
                anomaly.getPercentageChange(),
                anomaly.getCurrency(),
                anomaly.getHistoricalMean().doubleValue(),
                anomaly.getCurrency(),
                anomaly.getAbsoluteChange().doubleValue());

        InsightSeverity severity = switch (anomaly.getSeverity()) {
            case "HIGH" -> InsightSeverity.ALERT;
            case "MEDIUM" -> InsightSeverity.WARNING;
            default -> InsightSeverity.INFO;
        };

        return new FinancialInsight(
            UUID.randomUUID().toString(),
            InsightCategory.ANOMALY, title, narrative,
            List.of(
                String.format("+%.0f%% vs historical average",
                    anomaly.getPercentageChange()),
                String.format("%s %.2f above normal",
                    anomaly.getCurrency(),
                    anomaly.getAbsoluteChange().doubleValue())),
            null, 0.75, severity,
            language, YearMonth.now(),
            "STATISTICAL", Instant.now(), Map.of()
        );
    }

    private String categoryName(String code, boolean es) {
        return switch (code) {
            case "food_dining" -> es ? "Restaurantes" : "Dining";
            case "transportation" ->
                es ? "Transporte" : "Transportation";
            case "groceries" -> es ? "Supermercado" : "Groceries";
            case "shopping" -> es ? "Compras" : "Shopping";
            case "travel" -> es ? "Viajes" : "Travel";
            case "health" -> es ? "Salud" : "Health";
            default -> code;
        };
    }

    private String formatAmount(java.math.BigDecimal amount) {
        if (amount == null) return "0.00";
        return String.format("%,.2f", amount.doubleValue());
    }
}