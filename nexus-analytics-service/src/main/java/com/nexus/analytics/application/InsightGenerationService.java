package com.nexus.analytics.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.domain.model.*;
import com.nexus.analytics.domain.model.enums.*;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

/**
 * Insight Generation Service — Section 2/4 prompting patterns.
 *
 * Implements three patterns from the AI course:
 * 1. One-shot prompting (Section 2): example in user message
 * 2. System prompt design (Section 2): role + rules + constraints
 * 3. Chain-of-thought (Section 4): step-by-step anomaly reasoning
 *
 * No RAG, no tools, no agents.
 * The intelligence is in the prompt construction + structured output.
 *
 * Data gathering: Structured Concurrency for parallel queries.
 * Fallback: StatisticalInsightGenerator.java on AI failure.
 */
@Slf4j
@Service
public class InsightGenerationService {

    private final ChatClient insightsChatClient;
    private final ChatClient anomalyChatClient;
    private final AnalyticsQueryService queryService;
    private final StatisticalInsightGenerator statisticalFallback;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private final Timer insightGenerationTimer;
    private final Counter aiInsightCounter;
    private final Counter fallbackInsightCounter;

    private final String spendingUserTemplate;

    public InsightGenerationService(
            @Qualifier("insightsChatClient")
            ChatClient insightsChatClient,
            @Qualifier("anomalyChatClient")
            ChatClient anomalyChatClient,
            AnalyticsQueryService queryService,
            StatisticalInsightGenerator statisticalFallback,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) throws IOException {

        this.insightsChatClient = insightsChatClient;
        this.anomalyChatClient = anomalyChatClient;
        this.queryService = queryService;
        this.statisticalFallback = statisticalFallback;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.insightGenerationTimer = Timer.builder(
                        "analytics.insight.generation.duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

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

    /**
     * Main entry point — generates up to 5 insights for a period.
     *
     * Flow:
     * 1. Gather analytics data in parallel (Structured Concurrency)
     * 2. Validate data completeness (skip if < 70%)
     * 3. Generate spending insights (AI with one-shot prompt)
     * 4. Generate anomaly insights (AI with chain-of-thought)
     * 5. Merge, rank by relevance, return top 5
     */
    public List<FinancialInsight> generateInsights(
            String userId,
            YearMonth targetMonth,
            String language) {

        Observation obs = Observation.createNotStarted(
                "analytics.insight.generate", observationRegistry).start();

        Timer.Sample sample = Timer.start();

        try (Observation.Scope scope = obs.openScope()) {

            // Step 1: Gather data in parallel
            UserMonthlyAnalytics analytics = gatherAnalytics(
                    userId, targetMonth);

            if (analytics.dataCompleteness() < 0.70) {
                log.info("Insufficient data for insights: userId={}" +
                                " completeness={}", userId,
                        analytics.dataCompleteness());
                return List.of(FinancialInsight
                        .insufficientData(language));
            }

            // Step 2: Generate spending insights
            List<FinancialInsight> spendingInsights =
                    generateSpendingInsights(analytics, language);

            // Step 3: Generate anomaly insights (only if anomalies)
            List<FinancialInsight> anomalyInsights =
                    analytics.anomalies().isEmpty()
                            ? List.of()
                            : generateAnomalyInsights(
                            analytics.anomalies(), analytics, language);

            // Step 4: Merge + rank + cap at 5
            List<FinancialInsight> all = new ArrayList<>();
            all.addAll(spendingInsights);
            all.addAll(anomalyInsights);

            List<FinancialInsight> result = all.stream()
                    .filter(i -> i.relevanceScore() > 0.50)
                    .sorted(Comparator
                            .comparingDouble(FinancialInsight::relevanceScore)
                            .reversed())
                    .limit(5)
                    .toList();

            obs.event(Observation.Event.of(
                    "insights.generated." + result.size()));

            log.info("Insights generated: userId={} count={} " +
                    "month={}", userId, result.size(), targetMonth);

            return result.isEmpty()
                    ? List.of(FinancialInsight.insufficientData(language))
                    : result;

        } finally {
            sample.stop(insightGenerationTimer);
            obs.stop();
        }
    }

    // ── Step 1: Parallel data gathering ──────────────────────

    private UserMonthlyAnalytics gatherAnalytics(
            String userId, YearMonth month) {

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {

            var currentFuture = scope.fork(() ->
                    queryService.getMonthlyAnalytics(userId, month));

            var previousFuture = scope.fork(() ->
                    queryService.getMonthlyAnalytics(
                            userId, month.minusMonths(1)));

            var anomaliesFuture = scope.fork(() ->
                    queryService.getAnomalies(userId, month));

            scope.join();

            return queryService.mergeAnalytics(
                    userId, month,
                    currentFuture.get(),
                    previousFuture.get(),
                    anomaliesFuture.get());

        } catch (Exception e) {
            log.warn("Parallel data gathering failed: {}",
                    e.getMessage());
            return queryService.getMonthlyAnalyticsSafe(
                    userId, month);
        }
    }

    // ── Step 2: Spending insights (Section 2 one-shot) ────────

    @Retry(name = "openai-retry",
            fallbackMethod = "generateSpendingInsightsFallback")
    private List<FinancialInsight> generateSpendingInsights(
            UserMonthlyAnalytics analytics, String language) {

        Observation obs = Observation.createNotStarted(
                "analytics.ai.spending-insights",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Build user message from template
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

    // ── Step 3: Anomaly insights (Section 4 chain-of-thought) ─

    private List<FinancialInsight> generateAnomalyInsights(
            List<SpendingAnomaly> anomalies,
            UserMonthlyAnalytics analytics,
            String language) {

        Observation obs = Observation.createNotStarted(
                "analytics.ai.anomaly-insights",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Only process top 2 anomalies by severity
            return anomalies.stream()
                    .filter(a -> !"LOW".equals(a.getSeverity()))
                    .limit(2)
                    .map(anomaly ->
                            generateSingleAnomalyInsight(
                                    anomaly, analytics, language))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();

        } finally {
            obs.stop();
        }
    }

    @Retry(name = "openai-retry")
    private Optional<FinancialInsight> generateSingleAnomalyInsight(
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
            YearMonth period,
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
                                          YearMonth period,
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
                Instant.now(),
                Map.of()
        );
    }

    private String formatAmount(java.math.BigDecimal amount) {
        if (amount == null) return "0.00";
        return String.format("%,.2f", amount.doubleValue());
    }

    private String formatMerchants(
            List<Map<String, Object>> merchants) {
        if (merchants == null || merchants.isEmpty()) return "[]";
        return merchants.stream()
                .limit(3)
                .map(Object::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}