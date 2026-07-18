package com.nexus.audit.query.domain.exception;

/**
 * Thrown when the caller is authenticated but lacks the
 * COMPLIANCE_OFFICER/ADMIN role this service requires.
 * Maps to HTTP 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
