package com.nexus.saga.domain.exception;

/**
 * Thrown when a saga cannot be found by its ID or business key.
 * Typically caught at the Kafka consumer level — if a saga is not found,
 * the reply is likely a duplicate or belongs to a different instance.
 */
public class SagaNotFoundException extends RuntimeException {

    private final String identifier;

    public SagaNotFoundException(String identifier) {
        super("Saga not found: " + identifier);
        this.identifier = identifier;
    }

    public SagaNotFoundException(String identifier, String detail) {
        super("Saga not found: " + identifier + " — " + detail);
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }
}