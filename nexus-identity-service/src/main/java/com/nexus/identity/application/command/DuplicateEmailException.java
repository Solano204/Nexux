package com.nexus.identity.application.command;

/** Thrown when email is already registered (not soft-deleted). */
public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String message) { super(message); }
}
