package com.nexus.transaction.domain.model.enums;

/**
 * 16-state transaction lifecycle.
 * Every transition validated at application + database trigger layers.
 */
public enum TransactionStatus {
    INITIATED,
    FRAUD_CHECKING,
    FRAUD_CLEARED,
    FRAUD_REJECTED,
    BALANCE_RESERVING,
    BALANCE_RESERVED,
    RESERVE_FAILED,
    LEDGER_POSTING,
    LEDGER_POSTED,
    LEDGER_FAILED,
    COMPLETING,
    COMPLETED,
    FAILED,
    REVERSING,
    REVERSED,
    CANCELLED
}