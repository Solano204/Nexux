package com.nexus.identity.application.command;

/** Thrown when user has exhausted KYC retry limit (3 attempts in 30 days). */
public class KycRetryLimitExceededException extends RuntimeException {
    public KycRetryLimitExceededException(String message) { super(message); }
}
