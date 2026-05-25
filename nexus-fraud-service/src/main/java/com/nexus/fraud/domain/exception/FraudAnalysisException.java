package com.nexus.fraud.domain.exception;

/**
 * Thrown when fraud analysis fails irrecoverably.
 * Caught by the FraudReActAgent fallback — never surfaces to SAGA.
 */
public class FraudAnalysisException extends RuntimeException {

    public FraudAnalysisException(String message) {
        super(message);
    }

    public FraudAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}