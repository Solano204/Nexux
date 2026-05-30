package com.nexus.saga.domain.exception;

public class InvalidSagaStateException extends RuntimeException {
    public InvalidSagaStateException(String message) {
        super(message);
    }
}