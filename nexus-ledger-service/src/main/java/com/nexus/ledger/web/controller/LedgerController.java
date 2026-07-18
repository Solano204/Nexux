package com.nexus.ledger.web.controller;

import com.nexus.ledger.application.query.LedgerQueryService;
import com.nexus.ledger.domain.exception.UnauthorizedException;
import com.nexus.ledger.infrastructure.ai.LedgerExplainerService;
import com.nexus.ledger.web.dto.request.ExplainRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
public class LedgerController {

    private final LedgerQueryService queryService;
    private final LedgerExplainerService explainerService;

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> getBalance(
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

    @GetMapping("/accounts/{accountId}/entries")
    public ResponseEntity<?> getEntries(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        queryService.verifyAccountOwnership(accountId, extractUserId(httpRequest));
        return ResponseEntity.ok(
                queryService.getFullHistory(accountId, page, size));
    }

    @GetMapping("/accounts/{accountId}/summary/monthly")
    public ResponseEntity<?> getMonthlySummary(
            @PathVariable UUID accountId,
            @RequestParam int year,
            @RequestParam int month,
            HttpServletRequest httpRequest) {
        queryService.verifyAccountOwnership(accountId, extractUserId(httpRequest));
        return ResponseEntity.ok(
                queryService.getMonthlySummary(accountId, year, month));
    }

    @GetMapping("/transactions/{transactionId}/posting")
    public ResponseEntity<?> getPosting(
            @PathVariable UUID transactionId,
            HttpServletRequest httpRequest) {
        queryService.verifyPostingOwnership(transactionId, extractUserId(httpRequest));
        // Find posting by transaction ID
        var detail = queryService.getPostingDetail(transactionId);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    /**
     * AI Ledger Explainer — Section 3 (SSE Streaming).
     *
     * Returns Server-Sent Events — tokens stream as generated.
     * The user sees the explanation building in real time.
     */
    @PostMapping(
            value = "/accounts/{accountId}/explain",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> explainTransactions(
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