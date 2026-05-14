package com.nexus.transaction.domain.model.enums;

/**
 * 16-state transaction lifecycle.
 * Every state transition is validated at both application
 * and database (trigger) layers.
 */
public enum TransactionStatus {
    // --- Initiation
    INITIATED,
    // --- Fraud pipeline
    FRAUD_CHECKING,
    FRAUD_CLEARED,
    FRAUD_REJECTED,
    // --- Balance pipeline
    BALANCE_RESERVING,
    BALANCE_RESERVED,
    RESERVE_FAILED,
    // --- Ledger pipeline
    LEDGER_POSTING,
    LEDGER_POSTED,
    LEDGER_FAILED,
    // --- Completion
    COMPLETING,
    COMPLETED,
    // --- Failure and reversal
    FAILED,
    REVERSING,
    REVERSED,
    CANCELLED
}