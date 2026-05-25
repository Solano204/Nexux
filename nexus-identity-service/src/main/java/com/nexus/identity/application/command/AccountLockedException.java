package com.nexus.identity.application.command;

/** Thrown when account is locked due to failed login attempts. */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) { super(message); }
}
