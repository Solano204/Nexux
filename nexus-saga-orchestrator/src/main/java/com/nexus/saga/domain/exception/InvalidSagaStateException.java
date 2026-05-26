package com.nexus.saga.domain.exception;

/**
 * Thrown when an incoming reply or command requests a state transition
 * that is not valid from the saga's current step.
 *
 * E.g., receiving a LedgerPostedReply when the saga is already COMPLETED.
 * The transition guard in TransferStep.canTransitionTo() catches these
 * before they reach the state store.
 */
public class InvalidSagaStateException extends RuntimeException {

    private final String sagaId;
    private final String currentStep;
    private final String requestedStep;

    public InvalidSagaStateException(String message) {
        super(message);
        this.sagaId = null;
        this.currentStep = null;
        this.requestedStep = null;
    }

    public InvalidSagaStateException(String sagaId,
                                     String currentStep,
                                     String requestedStep) {
        super("Invalid state transition for saga " + sagaId
                + ": " + currentStep + " → " + requestedStep);
        this.sagaId = sagaId;
        this.currentStep = currentStep;
        this.requestedStep = requestedStep;
    }

    public String getSagaId() { return sagaId; }
    public String getCurrentStep() { return currentStep; }
    public String getRequestedStep() { return requestedStep; }
}