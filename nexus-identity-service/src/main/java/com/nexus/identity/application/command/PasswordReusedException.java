package com.nexus.identity.application.command;

/** Thrown when new password matches one of the last 5 used passwords. */
public class PasswordReusedException extends RuntimeException {
    public PasswordReusedException(String message) { super(message); }
}
