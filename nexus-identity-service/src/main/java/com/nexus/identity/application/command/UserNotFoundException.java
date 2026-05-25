package com.nexus.identity.application.command;

/** Thrown when user is not found (404). */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) { super(message); }
}
