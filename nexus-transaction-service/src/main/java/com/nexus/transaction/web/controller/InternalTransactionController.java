package com.nexus.transaction.web.controller;

import com.nexus.transaction.application.query.TransactionQueryService;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import com.nexus.transaction.infrastructure.persistence.TransactionRepository;
import com.nexus.transaction.web.dto.response.TransactionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * InternalTransactionController — Service-to-service endpoints.
 *
 * Callers:
 * - nexus-saga-orchestrator: status recovery on restart
 * - nexus-fraud-service: alternative fraud result delivery
 * - nexus-account-service: active transaction check before account closure
 * - nexus-health-monitor-lambda: metrics endpoint
 *
 * Path prefix: /internal/v1/transactions
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalTransactionController {

    private final TransactionRepository transactionRepository;
    private final TransactionQueryService queryService;

    private static final List<TransactionStatus> ACTIVE_STATUSES = List.of(
            TransactionStatus.INITIATED, TransactionStatus.FRAUD_CHECKING,
            TransactionStatus.FRAUD_CLEARED, TransactionStatus.BALANCE_RESERVING,
            TransactionStatus.BALANCE_RESERVED, TransactionStatus.LEDGER_POSTING,
            TransactionStatus.LEDGER_POSTED, TransactionStatus.COMPLETING,
            TransactionStatus.REVERSING
    );

    /**
     * Get transaction status — used by saga orchestrator for recovery.
     */
    @GetMapping("/transactions/{transactionId}/status")
    public ResponseEntity<Map<String, Object>> getTransactionStatus(
            @PathVariable("transactionId") UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new com.nexus.transaction.domain.exception
                        .TransactionNotFoundException("Not found: " + transactionId));

        return ResponseEntity.ok(Map.of(
                "transactionId", txn.getTransactionId().toString(),
                "status", txn.getStatus().name(),
                "sagaId", txn.getSagaId() != null ? txn.getSagaId().toString() : "none",
                "sagaStep", txn.getSagaStep() != null ? txn.getSagaStep() : "unknown",
                "initiatedAt", txn.getInitiatedAt().toString(),
                "isTerminal", Transaction.isTerminalStatus(txn.getStatus())
        ));
    }

    /**
     * Active transactions for an account — used by account service
     * to verify safe account closure (no in-flight transactions).
     */
    @GetMapping("/accounts/{accountId}/transactions/active")
    public ResponseEntity<List<Map<String, Object>>> getActiveTransactions(
            @PathVariable("accountId") UUID accountId) {
        // Find transactions where source or target is this account and status is active
        var all = transactionRepository.findAll().stream()
                .filter(t -> (accountId.equals(t.getSourceAccountId()) ||
                        accountId.equals(t.getTargetAccountId())) &&
                        ACTIVE_STATUSES.contains(t.getStatus()))
                .map(t -> Map.<String, Object>of(
                        "transactionId", t.getTransactionId().toString(),
                        "status", t.getStatus().name(),
                        "amount", t.getAmount().toPlainString(),
                        "initiatedAt", t.getInitiatedAt().toString()
                ))
                .toList();

        return ResponseEntity.ok(all);
    }

    /**
     * Force-compensate a stuck transaction — admin endpoint.
     */
    @PostMapping("/transactions/{transactionId}/force-compensate")
    public ResponseEntity<Map<String, Object>> forceCompensate(
            @PathVariable("transactionId") UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new com.nexus.transaction.domain.exception
                        .TransactionNotFoundException("Not found: " + transactionId));

        if (Transaction.isTerminalStatus(txn.getStatus())) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "reason", "Transaction is already in terminal state: " + txn.getStatus()
            ));
        }

        txn.markFailed("FORCE_COMPENSATED by admin");
        transactionRepository.save(txn);

        log.warn("Transaction force-compensated: txnId={} previousStatus={}",
                transactionId, txn.getStatus());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "transactionId", transactionId.toString(),
                "newStatus", txn.getStatus().name()
        ));
    }

    /**
     * Real-time transaction processing metrics.
     */
    @GetMapping("/transactions/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        long total = transactionRepository.count();
        long initiated = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.INITIATED).count();
        long completed = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.COMPLETED).count();
        long failed = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.FAILED).count();
        long fraudRejected = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.FRAUD_REJECTED).count();
        long active = transactionRepository.findAll().stream()
                .filter(t -> ACTIVE_STATUSES.contains(t.getStatus())).count();

        return ResponseEntity.ok(Map.of(
                "total", total,
                "initiated", initiated,
                "completed", completed,
                "failed", failed,
                "fraudRejected", fraudRejected,
                "activeInFlight", active
        ));
    }
}