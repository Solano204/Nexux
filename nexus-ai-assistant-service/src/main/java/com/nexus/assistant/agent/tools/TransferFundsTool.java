package com.nexus.assistant.agent.tools;

import com.nexus.assistant.infrastructure.client.AccountServiceClient;
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
 * Transfer Funds Tool — Initiates financial transfers.
 *
 * CRITICAL SECURITY: Tool description explicitly instructs LLM
 * to confirm BEFORE calling. This is a prompt-level guardrail.
 * The LLM will ask for confirmation rather than calling blindly.
 *
 * Additionally: validateOwnership() ensures source account
 * belongs to the authenticated user — no privilege escalation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferFundsTool {

    private final AccountServiceClient accountServiceClient;
    private final TransactionServiceClient transactionServiceClient;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "transfer_funds",
            description = """
            Initiates a money transfer between accounts.

            IMPORTANT: Before calling this tool you MUST:
            1. Confirm the exact amount with the user
            2. Confirm the exact destination account
            3. Show the user: amount + source + destination + current balance
            4. Wait for explicit "yes" or "confirm" from the user

            NEVER call this tool based on vague or implied consent.
            NEVER call this tool without showing the user the transfer summary first.

            Returns: transaction ID, status, estimated processing time.
            """
    )
    public String transferFunds(
            @ToolParam(description = "Source account UUID")
            String sourceAccountId,
            @ToolParam(description = "Target account UUID or account number")
            String targetAccountId,
            @ToolParam(description = "Amount as decimal string")
            String amount,
            @ToolParam(description = "Currency code (MXN, USD)")
            String currency,
            @ToolParam(description = "Transfer description/memo")
            String description) {

        Observation obs = Observation.createNotStarted(
                "ai.tool.transfer_funds", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            String userId = getCurrentUserId();

            // Security: verify source account belongs to user
            boolean isOwner = accountServiceClient
                    .isOwner(sourceAccountId, userId);

            if (!isOwner) {
                obs.event(Observation.Event.of(
                        "tool.transfer.unauthorized"));
                return """
                    {"error": "UNAUTHORIZED",
                     "message": "You can only transfer from your own accounts."}
                    """;
            }

            String result = transactionServiceClient
                    .initiateTransfer(userId, sourceAccountId,
                            targetAccountId, amount, currency, description);

            obs.event(Observation.Event.of("tool.transfer.initiated"));
            log.info("Transfer initiated via AI: userId={} amount={}",
                    userId, amount);

            return result;

        } catch (Exception e) {
            obs.error(e);
            log.error("Transfer tool failed: {}", e.getMessage(), e);
            return """
                {"error": "TRANSFER_FAILED",
                 "message": "Transfer could not be initiated. Please try again or use the app directly.",
                 "note": "No funds have been moved."}
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