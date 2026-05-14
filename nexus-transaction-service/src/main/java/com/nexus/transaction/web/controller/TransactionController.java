package com.nexus.transaction.web.controller;

import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.transaction.application.query.TransactionQueryService;
import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Transaction Controller — User-facing transaction endpoints.
 *
 * POST /api/v1/transactions/transfer  — Initiate transfer
 * POST /api/v1/transactions/payment   — Initiate payment
 * GET  /api/v1/transactions           — Transaction history
 * GET  /api/v1/transactions/{id}      — Transaction detail
 * GET  /api/v1/transactions/search    — Elasticsearch full-text search
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionCommandService commandService;
    private final TransactionQueryService queryService;
    private final Tracer tracer;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> initiateTransfer(
            @Valid @RequestBody InitiateTransactionRequest request,
            HttpServletRequest httpRequest) {

        UUID userId = extractUserId(httpRequest);
        String traceId = getTraceId();

        TransactionResponse response = commandService.initiateTransaction(
                request,
                userId,
                getClientIp(httpRequest),
                httpRequest.getHeader("X-Device-Fingerprint"),
                traceId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/payment")
    public ResponseEntity<TransactionResponse> initiatePayment(
            @Valid @RequestBody InitiateTransactionRequest request,
            HttpServletRequest httpRequest) {

        UUID userId = extractUserId(httpRequest);

        TransactionResponse response = commandService.initiateTransaction(
                request, userId,
                getClientIp(httpRequest),
                httpRequest.getHeader("X-Device-Fingerprint"),
                getTraceId());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> getHistory(
            HttpServletRequest request, Pageable pageable) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getTransactionHistory(userId, pageable));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable UUID transactionId,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getTransactionDetail(transactionId, userId));
    }

    @GetMapping("/search")
    public ResponseEntity<List<TransactionResponse>> searchTransactions(
            @RequestParam String query,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.searchTransactions(userId, query));
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new UnauthorizedException(
                "Authentication required");
        return UUID.fromString(userId);
    }

    private String getTraceId() {
        return tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : "no-trace";
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}