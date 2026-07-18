package com.nexus.ledger.web.controller;

import com.nexus.ledger.application.query.LedgerQueryService;
import com.nexus.ledger.domain.exception.UnauthorizedException;
import com.nexus.ledger.infrastructure.ai.LedgerExplainerService;
import com.nexus.ledger.web.dto.request.ExplainRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Ledger Controller — User-facing endpoints.
 *
 * GET /api/v1/ledger/accounts/{id}/balance
 * GET /api/v1/ledger/accounts/{id}/entries
 * GET /api/v1/ledger/accounts/{id}/summary/monthly
 * GET /api/v1/ledger/transactions/{txnId}/posting
 * POST /api/v1/ledger/accounts/{id}/explain  ← AI explainer (SSE)
 */
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Read-only double-entry ledger for the caller's own accounts, plus an AI transaction explainer. Ownership is checked against a local CDC-replicated copy of account-service's data, not a synchronous call.")
@SecurityRequirement(name = "X-User-Id")
public class LedgerController {

    private final LedgerQueryService queryService;
    private final LedgerExplainerService explainerService;

    @Operation(summary = "Get ledger balance", description = "The authoritative balance computed from ledger entries — not the Redis-cached fast-path balance account-service exposes.")
    @ApiResponse(responseCode = "200", description = "Balance retrieved")
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Account not yet replicated locally (rare, eventual-consistency window right after account creation)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> getBalance(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            HttpServletRequest httpRequest) {
        queryService.verifyAccountOwnership(accountId, extractUserId(httpRequest));
        var balance = queryService.getCurrentBalance(accountId);
        return ResponseEntity.ok(
                java.util.Map.of(
                        "accountId", accountId,
                        "balance", balance,
                        "currency", "MXN"
                ));
    }

    @Operation(summary = "Get ledger entries", description = "Manually paginated (page/size query params, not Spring's Pageable) full entry history — the precise double-entry record, not the summarized event history account-service exposes.")
    @ApiResponse(responseCode = "200", description = "Entries retrieved")
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/accounts/{accountId}/entries")
    public ResponseEntity<?> getEntries(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        queryService.verifyAccountOwnership(accountId, extractUserId(httpRequest));
        return ResponseEntity.ok(
                queryService.getFullHistory(accountId, page, size));
    }

    @Operation(summary = "Get monthly ledger summary", description = "Opening/closing balance, total debits/credits, and net change for the given month.")
    @ApiResponse(responseCode = "200", description = "Summary retrieved")
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/accounts/{accountId}/summary/monthly")
    public ResponseEntity<?> getMonthlySummary(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            @Parameter(description = "Year, e.g. 2026", required = true) @RequestParam int year,
            @Parameter(description = "Month, 1-12", required = true) @RequestParam int month,
            HttpServletRequest httpRequest) {
        queryService.verifyAccountOwnership(accountId, extractUserId(httpRequest));
        return ResponseEntity.ok(
                queryService.getMonthlySummary(accountId, year, month));
    }

    @Operation(
            summary = "Get the posting for a transaction",
            description = "A posting has two legs (debit + credit), possibly on different accounts " +
                    "(e.g. a transfer) — you're authorized if you own either side, not just the " +
                    "account you're calling from."
    )
    @ApiResponse(responseCode = "200", description = "Posting found")
    @ApiResponse(responseCode = "403", description = "Posting exists but the caller owns neither side of it",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No posting for this transaction ID")
    @GetMapping("/transactions/{transactionId}/posting")
    public ResponseEntity<?> getPosting(
            @Parameter(description = "Transaction UUID", required = true)
            @PathVariable UUID transactionId,
            HttpServletRequest httpRequest) {
        queryService.verifyPostingOwnership(transactionId, extractUserId(httpRequest));
        // Find posting by transaction ID
        var detail = queryService.getPostingDetail(transactionId);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    @Operation(
            summary = "Explain this account's transactions (streaming)",
            description = "AI-generated, plain-language explanation of recent ledger activity — " +
                    "Server-Sent Events, tokens arrive as generated. Same SSE consumption caveat as " +
                    "account-service's advisor chat endpoint."
    )
    @ApiResponse(responseCode = "200", description = "text/event-stream of explanation chunks")
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(
            value = "/accounts/{accountId}/explain",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> explainTransactions(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            @RequestBody ExplainRequest request,
            HttpServletRequest httpRequest) {

        UUID userId = extractUserId(httpRequest);
        queryService.verifyAccountOwnership(accountId, userId);

        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : "explain-" + accountId + "-" + userId;

        return explainerService.explainStreaming(
                accountId, request.message(), sessionId);
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new UnauthorizedException(
                "Authentication required");
        return UUID.fromString(userId);
    }
}