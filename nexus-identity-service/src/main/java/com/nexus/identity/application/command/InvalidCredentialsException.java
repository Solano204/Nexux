package com.nexus.identity.application.command;

// ─── Authentication exceptions ────────────────────────────────────────────────

/** Thrown when email/password combination is incorrect. Deliberately vague. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) { super(message); }
}

// ─── Registration exceptions ──────────────────────────────────────────────────

// ─── Password exceptions ──────────────────────────────────────────────────────

// ─── KYC exceptions ──────────────────────────────────────────────────────────

// ─── Generic exceptions ───────────────────────────────────────────────────────

