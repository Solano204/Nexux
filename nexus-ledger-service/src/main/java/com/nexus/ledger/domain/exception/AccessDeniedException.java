package com.nexus.ledger.domain.exception;

/**
 * Thrown when a user tries to access an account/posting they don't own.
 * Maps to HTTP 403.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
