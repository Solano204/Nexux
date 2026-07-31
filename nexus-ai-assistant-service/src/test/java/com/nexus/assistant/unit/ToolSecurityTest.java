package com.nexus.assistant.unit;

import com.nexus.assistant.agent.tools.AccountBalanceTool;
import com.nexus.assistant.agent.tools.FraudAlertsTool;
import com.nexus.assistant.agent.tools.TransferFundsTool;
import com.nexus.assistant.infrastructure.client.AccountServiceClient;
import com.nexus.assistant.infrastructure.client.FraudServiceClient;
import com.nexus.assistant.infrastructure.client.TransactionServiceClient;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Security-focused tests for AI tool classes — the LLM-invoked surface
 * where a prompt injection or careless tool implementation could let a
 * user read or move another user's money. Every tool must bind strictly
 * to SecurityContextHolder's authenticated principal, never a
 * caller-supplied userId.
 */
@ExtendWith(MockitoExtension.class)
class ToolSecurityTest {

    @Mock private AccountServiceClient accountServiceClient;
    @Mock private TransactionServiceClient transactionServiceClient;
    @Mock private FraudServiceClient fraudServiceClient;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(userId, null));
    }

    // ── TransferFundsTool ─────────────────────────────────────

    @Test
    void transferFundsRejectsWhenSourceAccountNotOwnedByCaller() {
        authenticateAs("user-1");
        TransferFundsTool tool = new TransferFundsTool(
                accountServiceClient, transactionServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.isOwner("other-users-account", "user-1")).thenReturn(false);

        String result = tool.transferFunds("other-users-account", "target-acct", "100.00", "MXN", "rent");

        assertThat(result).contains("UNAUTHORIZED");
        verifyNoInteractions(transactionServiceClient);
    }

    @Test
    void transferFundsProceedsWhenSourceAccountIsOwned() {
        authenticateAs("user-1");
        TransferFundsTool tool = new TransferFundsTool(
                accountServiceClient, transactionServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.isOwner("my-account", "user-1")).thenReturn(true);
        when(transactionServiceClient.initiateTransfer(
                eq("user-1"), eq("my-account"), eq("target-acct"), eq("100.00"), eq("MXN"), anyString()))
                .thenReturn("{\"transactionId\":\"txn-1\",\"status\":\"INITIATED\"}");

        String result = tool.transferFunds("my-account", "target-acct", "100.00", "MXN", "rent");

        assertThat(result).contains("txn-1");
    }

    @Test
    void transferFundsNeverUsesCallerSuppliedUserIdOnlySecurityContext() {
        authenticateAs("real-authenticated-user");
        TransferFundsTool tool = new TransferFundsTool(
                accountServiceClient, transactionServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.isOwner(anyString(), anyString())).thenReturn(true);
        when(transactionServiceClient.initiateTransfer(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("{}");

        tool.transferFunds("acct-1", "acct-2", "50.00", "MXN", "note");

        verify(accountServiceClient).isOwner("acct-1", "real-authenticated-user");
        verify(transactionServiceClient).initiateTransfer(
                eq("real-authenticated-user"), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void transferFundsReturnsGenericErrorOnDownstreamFailureWithoutMovingFunds() {
        authenticateAs("user-1");
        TransferFundsTool tool = new TransferFundsTool(
                accountServiceClient, transactionServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.isOwner(anyString(), anyString())).thenReturn(true);
        when(transactionServiceClient.initiateTransfer(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("transaction-service unreachable"));

        String result = tool.transferFunds("acct-1", "acct-2", "50.00", "MXN", "note");

        assertThat(result).contains("TRANSFER_FAILED");
        assertThat(result).contains("No funds have been moved");
        assertThat(result).doesNotContain("transaction-service unreachable");
    }

    // ── AccountBalanceTool ────────────────────────────────────

    @Test
    void accountBalanceToolThrowsWhenNoAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        AccountBalanceTool tool = new AccountBalanceTool(accountServiceClient, ObservationRegistry.NOOP);

        String result = tool.getAccountBalance(null);

        // The tool catches its own SecurityException and degrades to an
        // error payload rather than letting it propagate to the LLM runtime.
        assertThat(result).contains("BALANCE_UNAVAILABLE");
        verifyNoInteractions(accountServiceClient);
    }

    @Test
    void accountBalanceToolScopesToAuthenticatedUserForSpecificAccount() {
        authenticateAs("user-1");
        AccountBalanceTool tool = new AccountBalanceTool(accountServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.getBalance("acct-1", "user-1")).thenReturn("{\"balance\":500}");

        String result = tool.getAccountBalance("acct-1");

        assertThat(result).contains("500");
        verify(accountServiceClient).getBalance("acct-1", "user-1");
        verify(accountServiceClient, never()).getAllBalances(anyString());
    }

    @Test
    void accountBalanceToolFetchesAllAccountsWhenAccountIdBlank() {
        authenticateAs("user-1");
        AccountBalanceTool tool = new AccountBalanceTool(accountServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.getAllBalances("user-1")).thenReturn("[]");

        tool.getAccountBalance("  ");

        verify(accountServiceClient).getAllBalances("user-1");
        verify(accountServiceClient, never()).getBalance(anyString(), anyString());
    }

    @Test
    void accountBalanceToolDegradesGracefullyOnClientFailure() {
        authenticateAs("user-1");
        AccountBalanceTool tool = new AccountBalanceTool(accountServiceClient, ObservationRegistry.NOOP);
        when(accountServiceClient.getAllBalances("user-1")).thenThrow(new RuntimeException("circuit open"));

        String result = tool.getAccountBalance(null);

        assertThat(result).contains("BALANCE_UNAVAILABLE");
    }

    // ── FraudAlertsTool ───────────────────────────────────────

    @Test
    void fraudAlertsToolScopesToAuthenticatedUser() {
        authenticateAs("user-1");
        FraudAlertsTool tool = new FraudAlertsTool(fraudServiceClient, ObservationRegistry.NOOP);
        when(fraudServiceClient.getRecentAlertSummaries("user-1", 30)).thenReturn("[]");

        tool.getFraudAlerts(0);

        verify(fraudServiceClient).getRecentAlertSummaries("user-1", 30);
    }

    @Test
    void fraudAlertsToolUsesProvidedDaysBackWhenPositive() {
        authenticateAs("user-1");
        FraudAlertsTool tool = new FraudAlertsTool(fraudServiceClient, ObservationRegistry.NOOP);
        when(fraudServiceClient.getRecentAlertSummaries("user-1", 7)).thenReturn("[]");

        tool.getFraudAlerts(7);

        verify(fraudServiceClient).getRecentAlertSummaries("user-1", 7);
    }

    @Test
    void fraudAlertsToolDegradesGracefullyWithoutLeakingInternals() {
        authenticateAs("user-1");
        FraudAlertsTool tool = new FraudAlertsTool(fraudServiceClient, ObservationRegistry.NOOP);
        when(fraudServiceClient.getRecentAlertSummaries(anyString(), anyInt()))
                .thenThrow(new RuntimeException("fraud-service down"));

        String result = tool.getFraudAlerts(30);

        assertThat(result).contains("ALERTS_UNAVAILABLE");
        assertThat(result).doesNotContain("fraud-service down");
    }
}
