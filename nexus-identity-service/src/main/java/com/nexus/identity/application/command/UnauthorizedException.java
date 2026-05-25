package com.nexus.identity.application.command;

/** Thrown when X-User-Id header is missing or request is unauthenticated. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) { super(message); }
}
