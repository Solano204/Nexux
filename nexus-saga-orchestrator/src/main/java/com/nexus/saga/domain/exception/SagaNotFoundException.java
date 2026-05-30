package com.nexus.saga.domain.exception;

public class SagaNotFoundException extends RuntimeException {
    private final String sagaId;

    public SagaNotFoundException(String sagaId) {
        super("Saga not found: " + sagaId);
        this.sagaId = sagaId;
    }

    public String getSagaId() { return sagaId; }
}