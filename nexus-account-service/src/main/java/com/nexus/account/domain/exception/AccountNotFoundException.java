package com.nexus.account.domain.exception;

/**
 * Thrown when an account lookup fails.
 * Maps to HTTP 404 or 400 depending on context.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}