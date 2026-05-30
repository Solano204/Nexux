package com.nexus.kyc.domain.exception;

/**
 * KYC Processing Exception — unrecoverable pipeline failure.
 *
 * NOT a KYC rejection (that is a valid decision outcome).
 * Represents infrastructure/AI failures that prevent processing:
 * - S3 document retrieval failure after retries
 * - Both AI stages unavailable after Resilience4j retries
 * - MongoDB persistence failure for decision
 * - Invalid/corrupted document format
 *
 * Caught by KycVerificationService to produce FAILED status
 * with canRetry=true (system errors do NOT consume retry slots).
 */
public class KycProcessingException extends RuntimeException {

    public KycProcessingException(String message) {
        super(message);
    }

    public KycProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}