package com.nexus.transaction.web.controller;

import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.transaction.application.query.TransactionQueryService;
import com.nexus.transaction.domain.exception.UnauthorizedException;
import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/transactions") @RequiredArgsConstructor
@Tag(
        name = "Transactions",
        description = "Money movement — transfers, payments, history, and search. POST endpoints " +
                "return 202 Accepted, not 200/201: initiating a transaction only kicks off the saga " +
                "(fraud check → balance reservation → ledger posting) — it hasn't settled yet when " +
                "this responds. Poll GET /{transactionId} for the current status."
)
public class TransactionController {
    private final TransactionCommandService commandService;
    private final TransactionQueryService queryService;
    private final Tracer tracer;

    @Operation(
            summary = "Initiate a transfer between accounts",
            description = "Starts the transfer saga — returns immediately with status ACCEPTED once " +
                    "the transaction is durably recorded, before fraud/balance/ledger steps complete. " +
                    "idempotencyKey is required: retrying the exact same request with the same key " +
                    "(same userId + key) returns the original transaction instead of creating a " +
                    "duplicate — generate a fresh UUID per genuinely new transfer."
    )
    @ApiResponse(responseCode = "202", description = "Transfer accepted, saga started",
            content = @Content(examples = @ExampleObject(value = """
                    {"transactionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "status": "INITIATED", \
                    "sourceAccountId": "11111111-1111-1111-1111-111111111111", \
                    "targetAccountId": "22222222-2222-2222-2222-222222222222", \
                    "amount": 500.00, "currency": "MXN", "transactionType": "TRANSFER"}""")))
    @ApiResponse(responseCode = "400", description = "Validation failed (missing idempotencyKey, amount below 0.01, etc.)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> initiateTransfer(
            @Valid @RequestBody InitiateTransactionRequest request, HttpServletRequest httpRequest) {
        UUID userId = extractUserId(httpRequest);
        TransactionResponse response = commandService.initiateTransaction(request, userId, getClientIp(httpRequest), httpRequest.getHeader("X-Device-Fingerprint"), getTraceId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(
            summary = "Initiate a payment to a merchant",
            description = "Same saga and idempotency contract as /transfer — the only real difference " +
                    "is transactionType and how targetAccountId/targetAccountNumber are typically " +
                    "populated (a merchant settlement account rather than another NEXUS user)."
    )
    @ApiResponse(responseCode = "202", description = "Payment accepted, saga started")
    @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/payment")
    public ResponseEntity<TransactionResponse> initiatePayment(
            @Valid @RequestBody InitiateTransactionRequest request, HttpServletRequest httpRequest) {
        UUID userId = extractUserId(httpRequest);
        TransactionResponse response = commandService.initiateTransaction(request, userId, getClientIp(httpRequest), httpRequest.getHeader("X-Device-Fingerprint"), getTraceId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(
            summary = "Get the caller's transaction history",
            description = "Paginated, newest first, scoped to the authenticated user only — there is " +
                    "no way to list another user's transactions through this endpoint."
    )
    @ApiResponse(responseCode = "200", description = "Transaction history retrieved (empty page if none)")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> getHistory(
            HttpServletRequest request,
            @Parameter(description = "Standard Spring pagination — page, size, sort (e.g. sort=initiatedAt,desc)")
            Pageable pageable) {
        return ResponseEntity.ok(queryService.getTransactionHistory(extractUserId(request), pageable));
    }

    @Operation(
            summary = "Get transaction status/detail",
            description = "The status field here is what a client should poll after initiateTransfer/" +
                    "initiatePayment returns 202 — it moves through the saga's states " +
                    "(FRAUD_CHECKING → BALANCE_RESERVING → LEDGER_POSTING → COMPLETED, or a " +
                    "*_REJECTED/FAILED terminal state) as the saga orchestrator progresses it."
    )
    @ApiResponse(responseCode = "200", description = "Transaction found and belongs to the caller")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No transaction with this ID, or it belongs to another user",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @Parameter(description = "Transaction UUID", required = true)
            @PathVariable("transactionId") UUID transactionId, HttpServletRequest request) {
        return ResponseEntity.ok(queryService.getTransactionDetail(transactionId, extractUserId(request)));
    }

    @Operation(
            summary = "Search the caller's transactions",
            description = "Free-text search (description, merchant name, reference number) scoped to " +
                    "the authenticated user's own transactions — no pagination on this endpoint " +
                    "currently, results are capped internally rather than paged."
    )
    @ApiResponse(responseCode = "200", description = "Matching transactions (empty list if none)")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/search")
    public ResponseEntity<List<TransactionResponse>> searchTransactions(
            @Parameter(description = "Free-text query against description/merchant/reference", required = true)
            @RequestParam("query") String query, HttpServletRequest request) {
        return ResponseEntity.ok(queryService.searchTransactions(extractUserId(request), query));
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new UnauthorizedException("Authentication required");
        return UUID.fromString(userId);
    }
    private String getTraceId() { return tracer.currentSpan() != null ? tracer.currentSpan().context().traceId() : "no-trace"; }
    private String getClientIp(HttpServletRequest request) { String f = request.getHeader("X-Forwarded-For"); return f != null ? f.split(",")[0].trim() : request.getRemoteAddr(); }
}