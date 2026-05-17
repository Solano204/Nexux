package com.nexus.identity.application.command;


public class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException(String message) { super(message); }
}
