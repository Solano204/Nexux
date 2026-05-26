package com.nexus.ledger.domain.exception;

/**
 * Account not found in chart_of_accounts or is inactive.
 */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}