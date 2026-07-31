package com.nexus.saga.domain.model.transfer;

/**
 * TransferStep — State machine for the TransferSaga.
 *
 * Happy path:
 * STARTED → BALANCE_RESERVING → FRAUD_CHECKING → FRAUD_CLEARED →
 * LEDGER_POSTING → LEDGER_POSTED → BALANCE_FINALIZING →
 * BALANCE_FINALIZED → NOTIFICATION_SENDING → COMPLETED
 *
 * (There is no separately-persisted BALANCE_RESERVED step — reserving
 * funds and kicking off the fraud check happen as one saga-orchestrator
 * decision, not two. An earlier version of TransferSagaProcessor briefly
 * set currentStep to BALANCE_RESERVED right after already committing
 * FRAUD_CHECKING, which corrupted the persisted step — see the fix in
 * handleBalanceReserved. This enum previously modeled that stale
 * intermediate step as the only valid target of BALANCE_RESERVING; fixed
 * to match what the code actually does.)
 *
 * LEDGER_POSTING is the pivot: PostgreSQL row inserts in ledger-service
 * become immutable, real double-entry postings there. Failures BEFORE the
 * pivot compensate by releasing the balance reservation (RELEASING_BALANCE →
 * COMPENSATION_COMPLETED) — nothing durable happened yet. Failures/timeouts
 * AFTER the pivot (BALANCE_FINALIZING, NOTIFICATION_SENDING) must NOT take
 * that path: the ledger already posted, so "release the reservation" would
 * be the wrong compensation. Instead:
 * - BALANCE_FINALIZING timeout → retry (self-loop) — account-service's
 *   FinalizeTransferCommand handler is idempotent/status-aware, so silence
 *   almost always just means a lost reply, not a real failure. Only after
 *   exhausting retries does this escalate to PERMANENTLY_FAILED (manual
 *   reconciliation, e.g. via the ledger-service admin REVERSAL endpoint).
 * - NOTIFICATION_SENDING timeout → force-complete to COMPLETED. Money
 *   already moved; a slow/failed notification is not a saga failure.
 *
 * PERMANENTLY_FAILED: automated retry (of either compensation or
 * finalization) was exhausted — needs manual intervention.
 */
public enum TransferStep {
    // ── Happy path ───────────────────────────────────────────
    STARTED,
    BALANCE_RESERVING,
    FRAUD_CHECKING,
    FRAUD_CLEARED,
    FRAUD_REVIEW,           // Paused waiting for compliance officer
    LEDGER_POSTING,
    LEDGER_POSTED,
    BALANCE_FINALIZING,
    BALANCE_FINALIZED,
    NOTIFICATION_SENDING,
    COMPLETED,              // terminal — success

    // ── Failure + compensation paths ─────────────────────────
    BALANCE_RESERVATION_FAILED,
    FRAUD_REJECTED,
    LEDGER_FAILED,
    TIMED_OUT,
    RELEASING_BALANCE,
    BALANCE_RELEASED,
    COMPENSATION_COMPLETED, // terminal — failed but cleaned up

    // ── Last-resort terminal ─────────────────────────────────
    PERMANENTLY_FAILED;     // terminal — manual intervention needed

    public boolean isTerminal() {
        return this == COMPLETED
                || this == COMPENSATION_COMPLETED
                || this == PERMANENTLY_FAILED;
    }

    public boolean requiresCompensation() {
        return this == FRAUD_REJECTED
                || this == LEDGER_FAILED
                || this == TIMED_OUT;
    }

    /** Valid transitions from this step */
    public boolean canTransitionTo(TransferStep next) {
        return switch (this) {
            case STARTED -> next == BALANCE_RESERVING;
            case BALANCE_RESERVING ->
                    next == FRAUD_CHECKING ||
                            next == BALANCE_RESERVATION_FAILED;
            case FRAUD_CHECKING ->
                    next == FRAUD_CLEARED ||
                            next == FRAUD_REJECTED ||
                            next == FRAUD_REVIEW ||
                            next == TIMED_OUT;
            case FRAUD_REVIEW ->
                    next == FRAUD_CLEARED ||
                            next == FRAUD_REJECTED ||
                            next == TIMED_OUT;
            case FRAUD_CLEARED -> next == LEDGER_POSTING;
            case LEDGER_POSTING ->
                    next == LEDGER_POSTED ||
                            next == LEDGER_FAILED ||
                            next == TIMED_OUT;
            case LEDGER_POSTED -> next == BALANCE_FINALIZING;
            case BALANCE_FINALIZING ->
                    next == BALANCE_FINALIZED ||
                            next == BALANCE_FINALIZING ||   // retry (self-loop, post-pivot)
                            next == PERMANENTLY_FAILED;     // retries exhausted
            case BALANCE_FINALIZED -> next == NOTIFICATION_SENDING;
            case NOTIFICATION_SENDING ->
                    next == COMPLETED;   // post-pivot: never compensates, only completes
            case BALANCE_RESERVATION_FAILED ->
                    next == COMPENSATION_COMPLETED;
            case FRAUD_REJECTED, LEDGER_FAILED, TIMED_OUT ->
                    next == RELEASING_BALANCE;
            case RELEASING_BALANCE ->
                    next == BALANCE_RELEASED ||
                            next == RELEASING_BALANCE ||    // retry (self-loop)
                            next == PERMANENTLY_FAILED;     // retries exhausted
            case BALANCE_RELEASED ->
                    next == COMPENSATION_COMPLETED;
            default -> false;
        };
    }
}