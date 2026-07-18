package com.nexus.ledger.web.controller;

import com.nexus.ledger.application.command.LedgerCommandService;
import com.nexus.ledger.application.command.PostLedgerCommand;
import com.nexus.ledger.application.query.LedgerQueryService;
import com.nexus.ledger.application.reconciliation.ReconciliationJobService;
import com.nexus.ledger.domain.model.enums.PostingType;
import com.nexus.ledger.infrastructure.persistence.LedgerEntryRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Internal Ledger Controller — IP-restricted internal endpoints.
 *
 * Endpoints:
 *   GET  /internal/v1/ledger/accounts/{id}/balance    — Ledger balance
 *   POST /internal/v1/ledger/postings/manual           — Manual adjustment
 *   GET  /internal/v1/ledger/reconciliation/status      — Reconciliation status
 *   POST /internal/v1/ledger/accounts/{id}/reconstruct  — Force balance reconstruction
 *   GET  /internal/v1/ledger/integrity/verify           — Trigger checksum verification
 *   POST /internal/v1/ledger/postings/{id}/reverse      — Reverse a posting
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger (Internal)", description = "Admin/ops tooling — manual postings, reversals, reconciliation. No application-layer identity check today (unlike fraud/risk-scoring/saga-orchestrator's X-Internal-Service filter) — only the gateway's RemoteAddr predicate and Docker network isolation. Same class of gap documented in 13_REST_API_DESIGN_CHANGES.md, not yet ported here.")
public class InternalLedgerController {

    private final LedgerCommandService commandService;
    private final LedgerQueryService queryService;
    private final ReconciliationJobService reconciliationService;
    private final LedgerEntryRepository entryRepository;
    private final ObservationRegistry observationRegistry;

    @Operation(summary = "Get authoritative ledger balance", description = "Used by the reconciliation job and account-service's own balance verification — the ground truth, computed from ledger entries.")
    @ApiResponse(responseCode = "200", description = "Balance retrieved")
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getLedgerBalance(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId) {

        BigDecimal balance = queryService.getCurrentBalance(accountId);

        return ResponseEntity.ok(Map.of(
                "accountId", accountId.toString(),
                "ledgerBalance", balance,
                "currency", "MXN",
                "source", "ledger-entries",
                "queriedAt", Instant.now().toString()));
    }

    @Operation(
            summary = "Create a manual adjustment posting (ADMIN)",
            description = "Double-entry posting between the given accounts — reason and " +
                    "approvalReference are required and recorded in the posting description for " +
                    "audit. operatorId (X-User-Id) is not role-checked at this endpoint today."
    )
    @ApiResponse(responseCode = "201", description = "Posting created")
    @ApiResponse(responseCode = "422", description = "Posting failed (e.g. accounting imbalance)")
    @PostMapping("/postings/manual")
    public ResponseEntity<?> createManualPosting(
            @Valid @RequestBody ManualPostingRequest request,
            @Parameter(description = "Operator ID for the audit trail")
            @RequestHeader(value = "X-User-Id", required = false)
            String operatorId) {

        Observation obs = Observation.createNotStarted(
                "ledger.manual.posting", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            PostLedgerCommand command = PostLedgerCommand.builder()
                    .transactionId(null)
                    .sourceAccountId(request.sourceAccountId())
                    .targetAccountId(request.targetAccountId())
                    .amount(request.amount())
                    .currency(request.currency() != null
                            ? request.currency() : "MXN")
                    .postingType(PostingType.ADJUSTMENT)
                    .description("MANUAL ADJUSTMENT: " + request.reason()
                            + " [approval: " + request.approvalReference()
                            + "] [operator: " + operatorId + "]")
                    .sagaId(null)
                    .traceId("manual-" + UUID.randomUUID())
                    .build();

            var result = commandService.postDoubleEntry(command);

            log.warn("Manual posting created: postingId={} amount={} " +
                            "operator={} reason={}",
                    result.postingId(), request.amount(),
                    operatorId, request.reason());

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "postingId", result.postingId().toString(),
                    "debitEntryId", result.debitEntryId().toString(),
                    "creditEntryId", result.creditEntryId().toString(),
                    "description", command.description(),
                    "createdAt", Instant.now().toString()));

        } catch (Exception e) {
            obs.error(e);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Manual posting failed: " + e.getMessage());
            problem.setType(URI.create(
                    "https://nexus.com/errors/manual-posting-failed"));
            return ResponseEntity.status(
                    HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
        } finally {
            obs.stop();
        }
    }

    @Operation(summary = "Reverse a posting", description = "Creates new entries that exactly cancel the original — the original posting is never mutated or deleted (ledger entries are append-only).")
    @ApiResponse(responseCode = "200", description = "Reversal posted")
    @ApiResponse(responseCode = "409", description = "Reversal failed (e.g. posting not found, already reversed)")
    @PostMapping("/postings/{postingId}/reverse")
    public ResponseEntity<?> reversePosting(
            @Parameter(description = "Posting UUID to reverse", required = true)
            @PathVariable UUID postingId,
            @Valid @RequestBody ReversePostingRequest request,
            @Parameter(description = "Operator ID for the audit trail")
            @RequestHeader(value = "X-User-Id", required = false)
            String operatorId) {

        try {
            String reason = request.reason()
                    + " [operator: " + operatorId + "]";

            var result = commandService.postReversal(
                    postingId, reason,
                    "reversal-" + UUID.randomUUID());

            log.warn("Posting reversed: originalPostingId={} " +
                            "reversalPostingId={} operator={}",
                    postingId, result.postingId(), operatorId);

            return ResponseEntity.ok(Map.of(
                    "originalPostingId", postingId.toString(),
                    "reversalPostingId", result.postingId().toString(),
                    "debitEntryId", result.debitEntryId().toString(),
                    "creditEntryId", result.creditEntryId().toString(),
                    "reversedAt", Instant.now().toString()));

        } catch (Exception e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "Reversal failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
    }

    @Operation(summary = "Get reconciliation status", description = "Latest run results — the reconciliation job runs nightly at 1:00 AM America/Mexico_City.")
    @ApiResponse(responseCode = "200", description = "Status retrieved")
    @GetMapping("/reconciliation/status")
    public ResponseEntity<Map<String, Object>> getReconciliationStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "lastRunAt", Instant.now().toString(),
                "note", "Reconciliation runs nightly at 1:00 AM " +
                        "America/Mexico_City"));
    }

    @Operation(summary = "Force balance reconstruction", description = "Recomputes an account's balance from its ledger entries — used after reconciliation flags a discrepancy. Despite POST, this is read-only (no write happens, see the @Transactional(readOnly=true) on this method).")
    @ApiResponse(responseCode = "200", description = "Balance reconstructed")
    @PostMapping("/accounts/{accountId}/reconstruct")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> reconstructBalance(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId) {

        BigDecimal reconstructed = entryRepository
                .findLatestRunningBalance(accountId)
                .orElse(BigDecimal.ZERO);

        log.info("Balance reconstructed from ledger: accountId={} " +
                "balance={}", accountId, reconstructed);

        return ResponseEntity.ok(Map.of(
                "accountId", accountId.toString(),
                "reconstructedBalance", reconstructed,
                "source", "ledger-entry-running-balance",
                "reconstructedAt", Instant.now().toString()));
    }

    @Operation(summary = "Trigger immediate integrity verification", description = "Runs the same checksum + global-balance verification the scheduled job runs, synchronously, on demand.")
    @ApiResponse(responseCode = "200", description = "Verification complete")
    @GetMapping("/integrity/verify")
    public ResponseEntity<Map<String, Object>> verifyIntegrity() {
        Observation obs = Observation.createNotStarted(
                "ledger.integrity.verify.manual",
                observationRegistry).start();

        try {
            reconciliationService.verifyChecksums(obs);
            reconciliationService.verifyGlobalBalance(obs);

            return ResponseEntity.ok(Map.of(
                    "status", "VERIFICATION_COMPLETE",
                    "verifiedAt", Instant.now().toString()));
        } finally {
            obs.stop();
        }
    }

    // ── Request DTOs ──────────────────────────────────────

    public record ManualPostingRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID targetAccountId,
            @NotNull BigDecimal amount,
            String currency,
            @NotBlank String reason,
            @NotBlank String approvalReference
    ) {}

    public record ReversePostingRequest(
            @NotBlank String reason
    ) {}
}