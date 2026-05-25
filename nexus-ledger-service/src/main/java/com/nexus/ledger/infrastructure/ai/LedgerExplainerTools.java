package com.nexus.ledger.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.ledger.application.query.LedgerQueryService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Ledger Explainer Tools — Live data access for the AI explainer.
 *
 * These tools allow the AI to fetch live ledger data during
 * an explanation session. The AI decides when to call them
 * based on what the user is asking.
 *
 * Pattern: Tool Calling (Section 11)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerExplainerTools {

    private final LedgerQueryService queryService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "get_recent_ledger_entries",
            description = """
            Fetches the N most recent ledger entries for an account.
            Returns entries with amounts, types (debit/credit),
            descriptions, merchant names, and running balances.
            Use when user asks about recent transactions.
            """
    )
    public String getRecentEntries(
            @ToolParam(description = "Account UUID")
            String accountId,
            @ToolParam(description = "Number of entries to fetch " +
                    "(default 10, max 50)")
            int count) {

        Observation obs = Observation.createNotStarted(
                "ledger.tool.recent_entries", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            var entries = queryService.getRecentEntries(
                    UUID.fromString(accountId),
                    Math.min(count, 50));

            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            obs.error(e);
            return "{\"error\":\"Could not fetch ledger entries: " +
                    e.getMessage() + "\"}";
        } finally {
            obs.stop();
        }
    }

    @Tool(
            name = "get_monthly_summary",
            description = """
            Gets the income and expense summary for a specific month.
            Returns total received (income), total spent, net change,
            transaction count, and breakdown by category.
            Use when user asks about spending in a specific month.
            """
    )
    public String getMonthlySummary(
            @ToolParam(description = "Account UUID")
            String accountId,
            @ToolParam(description = "Year (e.g., 2025)")
            int year,
            @ToolParam(description = "Month number (1-12)")
            int month) {

        Observation obs = Observation.createNotStarted(
                "ledger.tool.monthly_summary",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            var summary = queryService.getMonthlySummary(
                    UUID.fromString(accountId), year, month);

            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            obs.error(e);
            return "{\"error\":\"Could not fetch monthly summary\"}";
        } finally {
            obs.stop();
        }
    }

    @Tool(
            name = "get_category_breakdown",
            description = """
            Gets spending broken down by category for a date range.
            Categories: TRANSFER, PAYMENT, FEE, INTEREST, etc.
            Use when user asks 'how much did I spend on X?'
            """
    )
    public String getCategoryBreakdown(
            @ToolParam(description = "Account UUID")
            String accountId,
            @ToolParam(description = "Start date ISO string " +
                    "(e.g., 2025-05-01)")
            String startDate,
            @ToolParam(description = "End date ISO string " +
                    "(e.g., 2025-05-31)")
            String endDate) {

        Observation obs = Observation.createNotStarted(
                "ledger.tool.category_breakdown",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            var breakdown = queryService.getCategoryBreakdown(
                    UUID.fromString(accountId), startDate, endDate);

            return objectMapper.writeValueAsString(breakdown);
        } catch (Exception e) {
            obs.error(e);
            return "{\"error\":\"Could not fetch category breakdown\"}";
        } finally {
            obs.stop();
        }
    }

    @Tool(
            name = "get_current_balance",
            description = """
            Gets the current ledger balance for an account.
            This is the authoritative balance from the financial ledger.
            Use when user asks 'what is my balance?'
            """
    )
    public String getCurrentBalance(
            @ToolParam(description = "Account UUID")
            String accountId) {

        Observation obs = Observation.createNotStarted(
                "ledger.tool.balance", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            var balance = queryService.getCurrentBalance(
                    UUID.fromString(accountId));

            return objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "accountId", accountId,
                            "currentBalance", balance,
                            "currency", "MXN"
                    ));
        } catch (Exception e) {
            obs.error(e);
            return "{\"error\":\"Could not fetch balance\"}";
        } finally {
            obs.stop();
        }
    }
}