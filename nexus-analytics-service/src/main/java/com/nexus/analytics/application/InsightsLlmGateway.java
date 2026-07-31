package com.nexus.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.domain.model.*;
import com.nexus.analytics.domain.model.enums.*;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Insights LLM Gateway - the two OpenAI call sites used by
 * InsightGenerationService (spending insights + per-anomaly insight),
 * split into their own bean so @Retry actually applies.
 *
 * Both were previously private methods called from within
 * InsightGenerationService itself (self-invocation) - that bypasses the
 * Spring AOP proxy entirely, so the retry (and, for spending insights,
 * the fallbackMethod) never triggered on failure. Living in its own bean
 * means InsightGenerationService calls them through the proxy instead.
 */
@Slf4j
@Component
public class InsightsLlmGateway {

    private final ChatClient insightsChatClient;
    private final ChatClient anomalyChatClient;
    private final StatisticalInsightGenerator statisticalFallback;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private final Counter aiInsightCounter;
    private final Counter fallbackInsightCounter;

    private final String spendingUserTemplate;

    public InsightsLlmGateway(
            @Qualifier("insightsChatClient") ChatClient insightsChatClient,
            @Qualifier("anomalyChatClient") ChatClient anomalyChatClient,
            StatisticalInsightGenerator statisticalFallback,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) throws IOException {

        this.insightsChatClient = insightsChatClient;
        this.anomalyChatClient = anomalyChatClient;
        this.statisticalFallback = statisticalFallback;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.aiInsightCounter =
                Counter.builder("analytics.insight.generation.total")
                        .tag("method", "AI").register(meterRegistry);

        this.fallbackInsightCounter =
                Counter.builder("analytics.insight.generation.total")
                        .tag("method", "STATISTICAL")
                        .register(meterRegistry);

        this.spendingUserTemplate = new ClassPathResource(
                "templates/analytics/spending-insights-user.st")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    // ── Spending insights (Section 2 one-shot) ────────

    @Retry(name = "openai-retry",
            fallbackMethod = "generateSpendingInsightsFallback")
    public List<FinancialInsight> generateSpendingInsights(
            UserMonthlyAnalytics analytics, String language) {

        Observation obs = Observation.createNotStarted(
                "analytics.ai.spending-insights",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            String userMessage = buildSpendingUserMessage(
                    analytics, language);

            String response = insightsChatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            List<FinancialInsight> insights =
                    parseInsightsResponse(response, analytics.period(),
                            language);

            aiInsightCounter.increment();
            obs.event(Observation.Event.of("ai.insight.success"));

            return insights;

        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    private List<FinancialInsight> generateSpendingInsightsFallback(
            UserMonthlyAnalytics analytics,
            String language,
            Exception ex) {

        log.warn("AI insight generation failed, using statistical" +
                " fallback: {}", ex.getMessage());
        fallbackInsightCounter.increment();
        return statisticalFallback.generate(analytics, language);
    }

    // ── Anomaly insights (Section 4 chain-of-thought) ─

    @Retry(name = "openai-retry")
    public Optional<FinancialInsight> generateSingleAnomalyInsight(
            SpendingAnomaly anomaly,
            UserMonthlyAnalytics analytics,
            String language) {
        try {
            String prompt = buildAnomalyPrompt(
                    anomaly, analytics, language);

            String response = anomalyChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            JsonNode root = objectMapper.readTree(response);
            if (root.has("insight")) {
                return Optional.of(parseInsight(
                        root.get("insight"),
                        analytics.period(), language));
            }
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Anomaly insight failed for category {}: {}",
                    anomaly.getCategory(), e.getMessage());
            return Optional.of(
                    statisticalFallback.generateAnomalyInsight(
                            anomaly, language));
        }
    }

    // ── Prompt builders ───────────────────────────────────────

    private String buildSpendingUserMessage(
            UserMonthlyAnalytics analytics, String language) {

        String categoryBreakdown = analytics.spendingByCategory()
                .stream()
                .map(c -> String.format(
                        "%s: {amount: %.2f, count: %d, previous: %.2f," +
                                " topMerchants: %s}",
                        c.category(),
                        c.amount().doubleValue(),
                        c.transactionCount(),
                        c.previousPeriodAmount() != null
                                ? c.previousPeriodAmount().doubleValue() : 0,
                        formatMerchants(c.topMerchants())))
                .collect(Collectors.joining("\n"));

        String topMerchants = analytics.topMerchants()
                .stream()
                .limit(5)
                .map(m -> "  " + m.toString())
                .collect(Collectors.joining("\n"));

        String anomalyStr = analytics.anomalies().isEmpty()
                ? "None detected"
                : analytics.anomalies().stream()
                .map(a -> String.format(
                        "%s spike in %s (+%.0f%%)",
                        a.getSeverity(), a.getCategory(),
                        a.getPercentageChange()))
                .collect(Collectors.joining(", "));

        return spendingUserTemplate
                .replace("{period}",
                        analytics.period().toString())
                .replace("{totalSpending}",
                        formatAmount(analytics.totalSpending()))
                .replace("{previousSpending}",
                        formatAmount(analytics.previousPeriodSpending()))
                .replace("{spendingChangePercent}",
                        String.format("%.1f",
                                analytics.spendingChangePercent()))
                .replace("{currency}", analytics.currency())
                .replace("{categoryBreakdown}", categoryBreakdown)
                .replace("{income}",
                        formatAmount(analytics.totalIncome()))
                .replace("{savingsRate}",
                        String.format("%.0f",
                                analytics.savingsRate() * 100))
                .replace("{previousSavingsRate}",
                        String.format("%.0f",
                                analytics.previousSavingsRate() * 100))
                .replace("{topMerchants}", topMerchants)
                .replace("{anomalies}", anomalyStr)
                .replace("{language}", language);
    }

    private String buildAnomalyPrompt(
            SpendingAnomaly anomaly,
            UserMonthlyAnalytics analytics,
            String language) {

        return """
            Analyze this spending anomaly:

            Category: %s
            Type: %s
            Severity: %s
            Current Amount: %s %s
            Historical Average: %s %s
            Change: +%.1f%%
            Z-Score: %.2f
            Top Contributing Merchants: %s

            User's overall context:
            - Total monthly spending: %s %s
            - Income: %s %s
            - Period: %s

            Language: %s
            """.formatted(
                anomaly.getCategory(),
                anomaly.getType(),
                anomaly.getSeverity(),
                formatAmount(anomaly.getCurrentValue()),
                anomaly.getCurrency(),
                formatAmount(anomaly.getHistoricalMean()),
                anomaly.getCurrency(),
                anomaly.getPercentageChange(),
                anomaly.getZScore(),
                anomaly.getTopContributingMerchants(),
                formatAmount(analytics.totalSpending()),
                analytics.currency(),
                formatAmount(analytics.totalIncome()),
                analytics.currency(),
                analytics.period(),
                language
        );
    }

    private List<FinancialInsight> parseInsightsResponse(
            String response,
            java.time.YearMonth period,
            String language) {

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode insightsNode = root.has("insights")
                    ? root.get("insights") : root;

            if (!insightsNode.isArray()) return List.of();

            List<FinancialInsight> result = new ArrayList<>();
            for (JsonNode node : insightsNode) {
                result.add(parseInsight(node, period, language));
            }
            return result;

        } catch (Exception e) {
            log.warn("Failed to parse insights response: {}",
                    e.getMessage());
            return List.of();
        }
    }

    private FinancialInsight parseInsight(JsonNode node,
                                          java.time.YearMonth period,
                                          String language) {

        List<String> dataPoints = new ArrayList<>();
        if (node.has("dataPoints")) {
            node.get("dataPoints").forEach(
                    d -> dataPoints.add(d.asText()));
        }

        InsightCategory category;
        try {
            category = InsightCategory.valueOf(
                    node.path("category").asText("SPENDING_TREND"));
        } catch (IllegalArgumentException e) {
            category = InsightCategory.SPENDING_TREND;
        }

        InsightSeverity severity;
        try {
            severity = InsightSeverity.valueOf(
                    node.path("severity").asText("INFO"));
        } catch (IllegalArgumentException e) {
            severity = InsightSeverity.INFO;
        }

        return new FinancialInsight(
                UUID.randomUUID().toString(),
                category,
                node.path("title").asText(""),
                node.path("narrative").asText(""),
                dataPoints,
                node.has("recommendation")
                        ? node.get("recommendation").asText() : null,
                node.path("relevanceScore").asDouble(0.5),
                severity,
                language,
                period,
                "AI",
                java.time.Instant.now(),
                java.util.Map.of()
        );
    }

    private String formatAmount(java.math.BigDecimal amount) {
        if (amount == null) return "0.00";
        return String.format("%,.2f", amount.doubleValue());
    }

    private String formatMerchants(
            List<java.util.Map<String, Object>> merchants) {
        if (merchants == null || merchants.isEmpty()) return "[]";
        return merchants.stream()
                .limit(3)
                .map(Object::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
