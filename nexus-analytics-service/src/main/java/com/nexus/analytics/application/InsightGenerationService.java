package com.nexus.analytics.application;

import com.nexus.analytics.domain.model.*;
import com.nexus.analytics.domain.model.enums.*;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

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

    private final InsightsLlmGateway llmGateway;
    private final AnalyticsQueryService queryService;
    private final ObservationRegistry observationRegistry;

    private final Timer insightGenerationTimer;

    public InsightGenerationService(
            InsightsLlmGateway llmGateway,
            AnalyticsQueryService queryService,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.llmGateway = llmGateway;
        this.queryService = queryService;
        this.observationRegistry = observationRegistry;

        this.insightGenerationTimer = Timer.builder(
                        "analytics.insight.generation.duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
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
                    llmGateway.generateSpendingInsights(analytics, language);

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

        // Forked subtasks run on their own virtual threads, which don't inherit
        // the calling thread's ThreadLocal trace context - without this snapshot,
        // any span the query layer opens for these Elasticsearch/Mongo reads would
        // detach from the "analytics.insight.generate" span opened in generateInsights().
        ContextSnapshot snapshot = ContextSnapshot.captureAll();

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow())) {

            var currentFuture = scope.fork(snapshot.wrap(() ->
                    queryService.getMonthlyAnalytics(userId, month)));

            var previousFuture = scope.fork(snapshot.wrap(() ->
                    queryService.getMonthlyAnalytics(
                            userId, month.minusMonths(1))));

            var anomaliesFuture = scope.fork(snapshot.wrap(() ->
                    queryService.getAnomalies(userId, month)));

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
                            llmGateway.generateSingleAnomalyInsight(
                                    anomaly, analytics, language))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();

        } finally {
            obs.stop();
        }
    }
}