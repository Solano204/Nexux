package com.nexus.identity.application.command;

/** Thrown when phone number is already registered. */
public class DuplicatePhoneException extends RuntimeException {
    public DuplicatePhoneException(String message) { super(message); }
}
