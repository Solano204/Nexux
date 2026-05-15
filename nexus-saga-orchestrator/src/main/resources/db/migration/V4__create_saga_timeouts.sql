package com.nexus.saga.domain.model.transfer;

/**
 * TransferStep — State machine for the TransferSaga.
 *
 * Happy path:
 * STARTED → BALANCE_RESERVING → BALANCE_RESERVED →
 * FRAUD_CHECKING → FRAUD_CLEARED → LEDGER_POSTING →
 * LEDGER_POSTED → BALANCE_FINALIZING → BALANCE_FINALIZED →
 * NOTIFICATION_SENDING → COMPLETED
 *
 * Failure paths all lead to compensation → COMPENSATION_COMPLETED.
 * PERMANENTLY_FAILED: compensation itself failed.
 */
public enum TransferStep {
    // ── Happy path ───────────────────────────────────────────
    STARTED,
    BALANCE_RESERVING,
    BALANCE_RESERVED,
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
                next == BALANCE_RESERVED ||
                next == BALANCE_RESERVATION_FAILED;
            case BALANCE_RESERVED -> next == FRAUD_CHECKING;
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
                next == TIMED_OUT;
            case BALANCE_FINALIZED -> next == NOTIFICATION_SENDING;
            case NOTIFICATION_SENDING ->
                next == COMPLETED || next == TIMED_OUT;
            case BALANCE_RESERVATION_FAILED ->
                next == COMPENSATION_COMPLETED;
            case FRAUD_REJECTED, LEDGER_FAILED, TIMED_OUT ->
                next == RELEASING_BALANCE;
            case RELEASING_BALANCE ->
                next == BALANCE_RELEASED ||
                next == PERMANENTLY_FAILED;
            case BALANCE_RELEASED ->
                next == COMPENSATION_COMPLETED;
            default -> false;
        };
    }
}