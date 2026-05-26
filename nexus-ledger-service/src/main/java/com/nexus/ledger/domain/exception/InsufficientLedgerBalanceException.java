package com.nexus.ledger.domain.exception;

/**
 * Ledger balance insufficient for the debit operation.
 * Secondary safety check — Account Service should catch this first.
 */
public class InsufficientLedgerBalanceException extends RuntimeException {
    public InsufficientLedgerBalanceException(String message) {
        super(message);
    }
}