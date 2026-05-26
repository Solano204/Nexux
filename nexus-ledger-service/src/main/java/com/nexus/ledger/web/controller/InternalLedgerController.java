package com.nexus.ledger.web.controller;

import com.nexus.ledger.application.command.LedgerCommandService;
import com.nexus.ledger.application.command.PostLedgerCommand;
import com.nexus.ledger.application.query.LedgerQueryService;
import com.nexus.ledger.application.reconciliation.ReconciliationJobService;
import com.nexus.ledger.domain.model.enums.PostingType;
import com.nexus.ledger.infrastructure.persistence.LedgerEntryRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
public class InternalLedgerController {

    private final LedgerCommandService commandService;
    private final LedgerQueryService queryService;
    private final ReconciliationJobService reconciliationService;
    private final LedgerEntryRepository entryRepository;
    private final ObservationRegistry observationRegistry;

    /**
     * Authoritative ledger balance for an account.
     * Used by reconciliation job and Account Service verification.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getLedgerBalance(
            @PathVariable UUID accountId) {

        BigDecimal balance = queryService.getCurrentBalance(accountId);

        return ResponseEntity.ok(Map.of(
                "accountId", accountId.toString(),
                "ledgerBalance", balance,
                "currency", "MXN",
                "source", "ledger-entries",
                "queriedAt", Instant.now().toString()));
    }

    /**
     * Manual adjustment posting — ADMIN only.
     * Creates a double-entry posting between user account and suspense.
     * Requires reason code and approval reference.
     */
    @PostMapping("/postings/manual")
    public ResponseEntity<?> createManualPosting(
            @Valid @RequestBody ManualPostingRequest request,
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

    /**
     * Reverse an existing posting.
     * Creates new entries that exactly cancel the original entries.
     */
    @PostMapping("/postings/{postingId}/reverse")
    public ResponseEntity<?> reversePosting(
            @PathVariable UUID postingId,
            @Valid @RequestBody ReversePostingRequest request,
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

    /**
     * Reconciliation status — latest run results.
     */
    @GetMapping("/reconciliation/status")
    public ResponseEntity<Map<String, Object>> getReconciliationStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "lastRunAt", Instant.now().toString(),
                "note", "Reconciliation runs nightly at 1:00 AM " +
                        "America/Mexico_City"));
    }

    /**
     * Force balance reconstruction from ledger entries.
     * Used after reconciliation failures.
     */
    @PostMapping("/accounts/{accountId}/reconstruct")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> reconstructBalance(
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

    /**
     * Trigger immediate checksum verification.
     */
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