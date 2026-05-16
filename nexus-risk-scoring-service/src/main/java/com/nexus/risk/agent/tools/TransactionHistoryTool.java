package com.nexus.risk.agent.tools;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Transaction History Tool — primary data source for risk scoring.
 * Fetches statistical summaries from Analytics Elasticsearch indices.
 * Computes: mean, stddev, coefficient of variation, income detection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionHistoryTool {

    private final ElasticsearchClient elasticsearchClient;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "transaction_history_tool",
            description = """
            Fetches transaction history and statistical summaries for a user.
            Returns: monthly spending/income totals, mean, stddev, coefficient
            of variation, category breakdown, transaction count, large
            transaction count (>MXN 10,000), and estimated savings rate.
            PRIMARY data source for credit and behavioral risk scoring.
            Always call this tool for any user with > 1 month of history.
            """
    )
    public String getTransactionHistory(
            @ToolParam(description = "User UUID")
            String userId,
            @ToolParam(description = "Months of history to analyze (default 12)")
            int monthsBack) {

        Observation obs = Observation.createNotStarted(
                "risk.tool.transaction_history",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            int months = monthsBack > 0 ? monthsBack : 12;

            // Query Analytics Elasticsearch for monthly summaries
            // In production: parse full Elasticsearch response
            // For clarity: returning structured mock result
            var result = java.util.Map.of(
                    "userId", userId,
                    "monthsAnalyzed", months,
                    "meanMonthlySpending", "28000.00",
                    "stdDevMonthlySpending", "4200.00",
                    "spendingCoefficientOfVariation", "0.15",
                    "meanMonthlyIncome", "35000.00",
                    "incomeConsistency", "0.92",
                    "estimatedSavingsRate", "0.20",
                    "totalTransactionCount", 156,
                    "largeTransactionsCount", 2,
                    "hasRegularIncome", true,
                    "dataAvailability", "FULL"
            );

            obs.event(Observation.Event.of("tool.success"));
            return toJson(result);

        } catch (Exception e) {
            obs.error(e);
            log.error("TransactionHistoryTool failed: userId={} {}",
                    userId, e.getMessage());
            return """
                {"error":"TOOL_FAILURE",
                 "message":"Transaction history unavailable",
                 "userId":"%s"}
                """.formatted(userId);
        } finally {
            obs.stop();
        }
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}