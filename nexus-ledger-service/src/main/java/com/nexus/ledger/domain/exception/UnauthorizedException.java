package com.nexus.ledger.domain.exception;

/**
 * Thrown when X-User-Id header is missing from request.
 * Maps to HTTP 401.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
