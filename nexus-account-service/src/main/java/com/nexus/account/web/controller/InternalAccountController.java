package com.nexus.account.web.controller;

import com.nexus.account.application.command.AccountCommandService;
import com.nexus.account.application.command.AccountCommandService.BalanceOperationResult;
import com.nexus.account.application.query.AccountQueryService;
import com.nexus.account.domain.model.Account;
import com.nexus.account.infrastructure.persistence.AccountRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * InternalAccountController — Service-to-service API endpoints.
 *
 * These endpoints are called by other Nexus services via the API Gateway's
 * Plane Bridge routing (internal path prefix). They are NOT exposed to
 * end users.
 *
 * Callers:
 * - nexus-saga-orchestrator: ReserveBalance, ReleaseBalance, FinalizeTransfer
 * - nexus-identity-service: CreateAccounts (after KYC approval)
 * - nexus-transaction-service: Balance lookup for validation
 * - nexus-fraud-service: Freeze/Unfreeze accounts
 * - nexus-health-monitor-lambda: Health check via /actuator/health
 *
 * Authentication: X-Internal-Service header validated by SecurityConfig.
 * These calls bypass JWT user authentication — they use service-to-service
 * trust (mTLS in production, shared secret in development).
 *
 * Path prefix: /internal/api/v1/accounts
 */
@Slf4j
@RestController
@RequestMapping("/internal/api/v1/accounts")
@RequiredArgsConstructor
public class InternalAccountController {

    private final AccountCommandService commandService;
    private final AccountQueryService queryService;
    private final AccountRepository accountRepository;
    private final ObservationRegistry observationRegistry;

    // ══════════════════════════════════════════════════════════
    // SAGA OPERATIONS — Called by nexus-saga-orchestrator
    // ══════════════════════════════════════════════════════════

    /**
     * Reserve balance for a transfer (TransferSaga Step 1).
     *
     * Called synchronously by the saga orchestrator as an alternative
     * to the Kafka saga.commands path. Both paths are idempotent.
     */
    @PostMapping("/{accountId}/reserve")
    public ResponseEntity<Map<String, Object>> reserveBalance(
            @PathVariable UUID accountId,
            @RequestBody ReserveBalanceRequest request,
            HttpServletRequest httpRequest) {

        Observation obs = Observation.createNotStarted(
                "internal.reserve-balance", observationRegistry).start();

        try {
            String traceId = extractTraceId(httpRequest);

            BalanceOperationResult result = commandService.reserveBalance(
                    accountId,
                    UUID.fromString(request.transactionId()),
                    request.amount(),
                    traceId);

            if (result.success()) {
                obs.event(Observation.Event.of("reserve.success"));
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "reservedAmount", result.amount().toPlainString(),
                        "newAvailableBalance", result.newAvailableBalance() != null
                                ? result.newAvailableBalance().toPlainString() : "0",
                        "transactionId", result.transactionId(),
                        "idempotentReplay", result.idempotentReplay()
                ));
            } else {
                obs.event(Observation.Event.of("reserve.failed"));
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(Map.of(
                                "success", false,
                                "reason", result.failureReason()
                        ));
            }
        } finally {
            obs.stop();
        }
    }

    /**
     * Release reserved balance (TransferSaga compensation).
     */
    @PostMapping("/{accountId}/release")
    public ResponseEntity<Map<String, Object>> releaseBalance(
            @PathVariable UUID accountId,
            @RequestBody ReleaseBalanceRequest request,
            HttpServletRequest httpRequest) {

        String traceId = extractTraceId(httpRequest);

        BalanceOperationResult result = commandService.releaseBalance(
                accountId,
                UUID.fromString(request.transactionId()),
                request.amount(),
                traceId);

        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "releasedAmount", result.amount().toPlainString(),
                    "transactionId", result.transactionId(),
                    "idempotentReplay", result.idempotentReplay()
            ));
        } else {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("success", false, "reason", result.failureReason()));
        }
    }

    /**
     * Finalize transfer — debit source + credit target atomically.
     * Called by saga orchestrator after ledger confirmation.
     */
    @PostMapping("/finalize-transfer")
    public ResponseEntity<Map<String, Object>> finalizeTransfer(
            @RequestBody FinalizeTransferRequest request,
            HttpServletRequest httpRequest) {

        String traceId = extractTraceId(httpRequest);

        try {
            commandService.finalizeTransfer(
                    UUID.fromString(request.sourceAccountId()),
                    UUID.fromString(request.targetAccountId()),
                    UUID.fromString(request.transactionId()),
                    request.amount(),
                    traceId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "transactionId", request.transactionId()
            ));
        } catch (Exception e) {
            log.error("FinalizeTransfer failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "reason", e.getMessage()
                    ));
        }
    }

    // ══════════════════════════════════════════════════════════
    // ACCOUNT CREATION — Called by nexus-identity-service
    // ══════════════════════════════════════════════════════════

    /**
     * Create default accounts for a newly KYC-approved user.
     * Creates CHECKING + SAVINGS accounts in a single operation.
     */
    @PostMapping("/create-defaults")
    public ResponseEntity<Map<String, Object>> createDefaultAccounts(
            @RequestBody CreateDefaultAccountsRequest request,
            HttpServletRequest httpRequest) {

        String traceId = extractTraceId(httpRequest);

        try {
            var accounts = commandService.createDefaultAccounts(
                    UUID.fromString(request.userId()),
                    request.currency() != null ? request.currency() : "MXN",
                    traceId);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of(
                            "success", true,
                            "userId", request.userId(),
                            "accountsCreated", accounts.size(),
                            "accounts", accounts
                    ));
        } catch (Exception e) {
            log.error("CreateDefaultAccounts failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "success", false,
                            "reason", e.getMessage()
                    ));
        }
    }

    // ══════════════════════════════════════════════════════════
    // COMPLIANCE — Called by nexus-fraud-service
    // ══════════════════════════════════════════════════════════

    /**
     * Freeze an account (compliance/fraud action).
     */
    @PostMapping("/{accountId}/freeze")
    public ResponseEntity<Map<String, Object>> freezeAccount(
            @PathVariable UUID accountId,
            @RequestBody FreezeAccountRequest request) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountId));

        account.freeze(request.reason());
        accountRepository.save(account);

        log.info("Account frozen: accountId={} reason={}",
                accountId, request.reason());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "accountId", accountId.toString(),
                "status", "FROZEN"
        ));
    }

    /**
     * Unfreeze an account.
     */
    @PostMapping("/{accountId}/unfreeze")
    public ResponseEntity<Map<String, Object>> unfreezeAccount(
            @PathVariable UUID accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountId));

        account.unfreeze();
        accountRepository.save(account);

        log.info("Account unfrozen: accountId={}", accountId);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "accountId", accountId.toString(),
                "status", "ACTIVE"
        ));
    }

    // ══════════════════════════════════════════════════════════
    // BALANCE LOOKUP — Called by nexus-transaction-service
    // ══════════════════════════════════════════════════════════

    /**
     * Internal balance check (non-cached, direct PostgreSQL read).
     * Used by transaction service for pre-validation before saga initiation.
     */
    @GetMapping("/{accountId}/balance-check")
    public ResponseEntity<Map<String, Object>> checkBalance(
            @PathVariable UUID accountId) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountId));

        return ResponseEntity.ok(Map.of(
                "accountId", accountId.toString(),
                "availableBalance", account.getAvailableBalance().toPlainString(),
                "reservedAmount", account.getReservedAmount().toPlainString(),
                "totalBalance", account.getTotalBalance() != null
                        ? account.getTotalBalance().toPlainString()
                        : account.getAvailableBalance()
                        .add(account.getReservedAmount()).toPlainString(),
                "currency", account.getCurrency(),
                "status", account.getStatus().name(),
                "dailyLimitRemaining", account.getDailyLimitRemaining().toPlainString()
        ));
    }

    /**
     * Lookup accounts by userId — used by identity service.
     */
    @GetMapping("/by-user/{userId}")
    public ResponseEntity<List<?>> getAccountsByUser(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(queryService.getUserAccounts(userId));
    }

    // ══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════

    private String extractTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null) {
            traceId = request.getHeader("X-B3-TraceId");
        }
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }

    // ══════════════════════════════════════════════════════════
    // REQUEST DTOs
    // ══════════════════════════════════════════════════════════

    public record ReserveBalanceRequest(
            String transactionId,
            BigDecimal amount
    ) {}

    public record ReleaseBalanceRequest(
            String transactionId,
            BigDecimal amount
    ) {}

    public record FinalizeTransferRequest(
            String sourceAccountId,
            String targetAccountId,
            String transactionId,
            BigDecimal amount
    ) {}

    public record CreateDefaultAccountsRequest(
            String userId,
            String currency
    ) {}

    public record FreezeAccountRequest(
            String reason
    ) {}
}