package com.nexus.analytics.domain.model;

import com.nexus.analytics.domain.model.enums.InsightCategory;
import com.nexus.analytics.domain.model.enums.InsightSeverity;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FinancialInsight — Structured output from AI insight generation (Section 3).
 *
 * Both AI and statistical fallback produce this record.
 * The UI always receives the same structure regardless of generation method.
 */
public record FinancialInsight(
        String insightId,
        InsightCategory category,
        String title,
        String narrative,
        List<String> dataPoints,
        String recommendation,
        double relevanceScore,
        InsightSeverity severity,
        String language,
        YearMonth period,
        String generationMethod,     // "AI" or "STATISTICAL"
        Instant generatedAt,
        Map<String, Object> rawData  // Audit trail — what drove this insight
) {

    public static FinancialInsight insufficientData(String lang) {
        String msg = "es".equals(lang)
                ? "Aún no hay suficientes datos para generar insights. " +
                "Regresa después de más transacciones."
                : "Not enough data yet to generate insights. " +
                "Come back after more transactions.";

        return new FinancialInsight(
                UUID.randomUUID().toString(),
                InsightCategory.SPENDING_TREND,
                "es".equals(lang) ? "Datos insuficientes"
                        : "Insufficient data",
                msg,
                List.of(),
                null,
                0.3,
                InsightSeverity.INFO,
                lang,
                YearMonth.now(),
                "SYSTEM",
                Instant.now(),
                Map.of()
        );
    }
}