package com.nexus.transaction.application.saga;

import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import com.nexus.transaction.infrastructure.persistence.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * TransactionSagaOrchestrator — SAGA lifecycle management.
 *
 * Handles:
 * 1. Stuck transaction detection — transactions in non-terminal states
 *    for longer than expected (FRAUD_CHECKING > 2min, etc.)
 * 2. Timeout compensation — transactions past 24h expiry
 * 3. Retry logic for failed compensation attempts
 *
 * The primary SAGA flow is CHOREOGRAPHY-based:
 *   TransactionInitiated → FraudResultConsumer → SagaReplyConsumer → LedgerResultConsumer
 *
 * This orchestrator handles the EXCEPTION paths:
 * timeout, stuck, and permanently-failed scenarios.
 */
@Slf4j
@Component
public class TransactionSagaOrchestrator {

    private final TransactionRepository transactionRepository;
    private final TransactionCommandService commandService;
    private final Counter timeoutCounter;
    private final Counter stuckCounter;

    private static final List<TransactionStatus> ACTIVE_STATUSES = List.of(
            TransactionStatus.INITIATED,
            TransactionStatus.FRAUD_CHECKING,
            TransactionStatus.FRAUD_CLEARED,
            TransactionStatus.BALANCE_RESERVING,
            TransactionStatus.BALANCE_RESERVED,
            TransactionStatus.LEDGER_POSTING
    );

    // Max time per state before considered stuck
    private static final Duration FRAUD_CHECK_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration BALANCE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration LEDGER_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration GENERAL_TIMEOUT = Duration.ofHours(24);

    public TransactionSagaOrchestrator(
            TransactionRepository transactionRepository,
            TransactionCommandService commandService,
            MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.commandService = commandService;
        this.timeoutCounter = Counter.builder("transaction.timeout.compensated.total")
                .description("Transactions auto-compensated due to timeout")
                .register(meterRegistry);
        this.stuckCounter = Counter.builder("transaction.stuck.detected.total")
                .description("Stuck transactions detected")
                .register(meterRegistry);
    }

    /**
     * Detect and compensate stuck transactions — runs every 2 minutes.
     *
     * Finds transactions in active (non-terminal) states that have
     * exceeded their expected processing time. Forces compensation
     * to prevent funds from being locked indefinitely.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void detectAndCompensateStuckTransactions() {
        Instant stuckThreshold = Instant.now().minus(GENERAL_TIMEOUT);

        List<Transaction> stuckTransactions = transactionRepository
                .findStuckTransactions(ACTIVE_STATUSES, stuckThreshold);

        if (stuckTransactions.isEmpty()) return;

        log.warn("Found {} stuck transactions to compensate",
                stuckTransactions.size());

        for (Transaction txn : stuckTransactions) {
            try {
                Duration age = Duration.between(txn.getInitiatedAt(), Instant.now());
                String reason = String.format(
                        "TIMEOUT: Transaction stuck in %s for %s hours",
                        txn.getStatus(), age.toHours());

                log.warn("Compensating stuck transaction: txnId={} status={} age={}h",
                        txn.getTransactionId(), txn.getStatus(), age.toHours());

                txn.markFailed(reason);
                transactionRepository.save(txn);

                // If balance was reserved, trigger release
                if (txn.getStatus() == TransactionStatus.BALANCE_RESERVED ||
                        txn.getStatus() == TransactionStatus.LEDGER_POSTING) {
                    commandService.processBalanceResult(
                            txn.getTransactionId(), txn.getSagaId(),
                            false, reason, "timeout-orchestrator");
                }

                timeoutCounter.increment();
                stuckCounter.increment();

            } catch (Exception e) {
                log.error("Failed to compensate stuck transaction txnId={}: {}",
                        txn.getTransactionId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Detect fraud check timeouts — runs every 30 seconds.
     *
     * If a transaction has been in FRAUD_CHECKING for more than
     * 2 minutes, the fraud service is likely down. Force-fail
     * with compensation.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void detectFraudCheckTimeouts() {
        Instant threshold = Instant.now().minus(FRAUD_CHECK_TIMEOUT);

        List<Transaction> timedOut = transactionRepository
                .findStuckTransactions(
                        List.of(TransactionStatus.FRAUD_CHECKING),
                        threshold);

        for (Transaction txn : timedOut) {
            try {
                log.warn("Fraud check timeout for txnId={}, failing transaction",
                        txn.getTransactionId());

                txn.markFailed("FRAUD_CHECK_TIMEOUT: No response from fraud service");
                transactionRepository.save(txn);

                timeoutCounter.increment();
            } catch (Exception e) {
                log.error("Failed to handle fraud timeout for txnId={}: {}",
                        txn.getTransactionId(), e.getMessage());
            }
        }
    }
}