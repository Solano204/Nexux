package com.nexus.assistant.agent.tools;

import com.nexus.assistant.infrastructure.client.TransactionServiceClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Transaction History Tool — Searches transaction history via Elasticsearch.
 * Returns transactions + pre-computed category totals.
 * LLM interprets the data; service does the arithmetic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionHistoryTool {

    private final TransactionServiceClient transactionServiceClient;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "get_transaction_history",
            description = """
            Retrieves transaction history for an account.
            Supports filters: startDate, endDate (ISO dates),
            minAmount, maxAmount, category, merchantName.
            Returns transactions AND pre-computed category totals.
            Default limit: 20 transactions. Max: 50.
            Use for spending analysis, finding specific transactions,
            or understanding patterns.
            """
    )
    public String getTransactionHistory(
            @ToolParam(description = "Account UUID")
            String accountId,
            @ToolParam(description = "Start date YYYY-MM-DD, null for no filter")
            String startDate,
            @ToolParam(description = "End date YYYY-MM-DD, null for no filter")
            String endDate,
            @ToolParam(description = "Merchant name filter, null for all")
            String merchantName,
            @ToolParam(description = "Number of results, default 20")
            int limit) {

        Observation obs = Observation.createNotStarted(
                "ai.tool.get_transaction_history",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            String userId = getCurrentUserId();

            String result = transactionServiceClient
                    .searchTransactions(userId, accountId,
                            startDate, endDate, merchantName,
                            Math.min(limit > 0 ? limit : 20, 50));

            obs.event(Observation.Event.of("tool.history.success"));
            return result;

        } catch (Exception e) {
            obs.error(e);
            return """
                {"error": "HISTORY_UNAVAILABLE",
                 "message": "Transaction history temporarily unavailable."}
                """;
        } finally {
            obs.stop();
        }
    }

    private String getCurrentUserId() {
        return SecurityContextHolder.getContext()
                .getAuthentication().getName();
    }
}