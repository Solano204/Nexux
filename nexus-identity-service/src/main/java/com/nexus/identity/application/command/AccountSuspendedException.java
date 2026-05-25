package com.nexus.identity.application.command;

/** Thrown when account has been administratively suspended. */
public class AccountSuspendedException extends RuntimeException {
    public AccountSuspendedException(String message) { super(message); }
}
