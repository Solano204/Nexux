package com.nexus.ledger.domain.exception;

/**
 * CRITICAL exception — double-entry invariant violated.
 * total_debit != total_credit in a posting.
 * This should NEVER happen in production — three layers prevent it.
 */
public class AccountingImbalanceException extends RuntimeException {
    public AccountingImbalanceException(String message) {
        super(message);
    }
}