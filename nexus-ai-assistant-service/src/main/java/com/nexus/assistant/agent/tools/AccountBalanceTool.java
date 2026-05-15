package com.nexus.assistant.agent.tools;

import com.nexus.assistant.infrastructure.client.AccountServiceClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Account Balance Tool — Retrieves live account balance.
 *
 * Security: getCurrentUserId() from SecurityContext ensures
 * the AI can ONLY access the authenticated user's accounts.
 * Even if a prompt injection requests another user's balance,
 * the tool is bound to the authenticated userId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountBalanceTool {

    private final AccountServiceClient accountServiceClient;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "get_account_balance",
            description = """
            Retrieves the current balance of one or all accounts
            for the authenticated user. Returns: account number,
            type, available balance, reserved amount, currency.
            Use before any financial action that depends on funds.
            Pass null accountId to get ALL accounts.
            """
    )
    public String getAccountBalance(
            @ToolParam(description = "Account UUID, or null for all accounts")
            String accountId) {

        Observation obs = Observation.createNotStarted(
                "ai.tool.get_account_balance", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            String userId = getCurrentUserId();

            String result = accountId != null && !accountId.isBlank()
                    ? accountServiceClient.getBalance(accountId, userId)
                    : accountServiceClient.getAllBalances(userId);

            obs.event(Observation.Event.of("tool.balance.success"));
            return result;

        } catch (Exception e) {
            obs.error(e);
            log.warn("Balance tool failed: {}", e.getMessage());
            return """
                {"error": "BALANCE_UNAVAILABLE",
                 "message": "Account balance temporarily unavailable. Please try again."}
                """;
        } finally {
            obs.stop();
        }
    }

    private String getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new SecurityException("No authenticated user");
        }
        return auth.getName();
    }
}