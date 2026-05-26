package com.nexus.saga.domain.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * SagaFailureContext — input to SagaFailureExplainerService.
 *
 * Carries all the data needed to generate a user-facing failure explanation
 * without exposing internal system details to the AI prompt builder.
 *
 * Static factory methods for each failure scenario ensure consistent,
 * well-typed context construction from the raw reply/event objects.
 */
@Builder
public record SagaFailureContext(

        SagaType sagaType,
        FailureType failureType,
        String userId,
        BigDecimal amount,
        String currency,
        String targetName,             // recipient name if transfer, null otherwise
        List<String> technicalReasons, // internal — NOT sent to AI prompt
        boolean fundsWereReserved,
        boolean fundsAreReleased,
        boolean canRetry,
        String language                // "es", "en", "pt"

) {

    public enum SagaType {
        TRANSFER, ONBOARDING
    }

    public enum FailureType {
        FRAUD_REJECTED,
        INSUFFICIENT_FUNDS,
        KYC_REJECTED,
        SAGA_TIMEOUT,
        COMPENSATION_FAILED
    }

    // ── Static factories ─────────────────────────────────────

    /**
     * Transfer blocked by fraud detection.
     * Funds were reserved and will be released by compensation.
     */
    public static SagaFailureContext fraudRejected(
            String userId,
            BigDecimal amount,
            String currency,
            String targetName,
            List<String> triggeringFactors,
            String language) {

        return SagaFailureContext.builder()
                .sagaType(SagaType.TRANSFER)
                .failureType(FailureType.FRAUD_REJECTED)
                .userId(userId)
                .amount(amount)
                .currency(currency)
                .targetName(targetName)
                .technicalReasons(triggeringFactors != null
                        ? triggeringFactors : List.of())
                .fundsWereReserved(true)
                .fundsAreReleased(false)
                .canRetry(true)
                .language(language)
                .build();
    }

    /**
     * Transfer failed because the source account had insufficient funds.
     * No reservation was made — no compensation needed.
     */
    public static SagaFailureContext insufficientFunds(
            String userId,
            BigDecimal amount,
            String currency,
            String targetName,
            String reason,
            String language) {

        return SagaFailureContext.builder()
                .sagaType(SagaType.TRANSFER)
                .failureType(FailureType.INSUFFICIENT_FUNDS)
                .userId(userId)
                .amount(amount)
                .currency(currency)
                .targetName(targetName)
                .technicalReasons(List.of(reason))
                .fundsWereReserved(false)
                .fundsAreReleased(false)
                .canRetry(true)
                .language(language)
                .build();
    }

    /**
     * KYC identity verification failed.
     * canRetry depends on how many attempts remain.
     */
    public static SagaFailureContext kycRejected(
            String userId,
            List<String> rejectionReasons,
            boolean canRetry,
            String language) {

        return SagaFailureContext.builder()
                .sagaType(SagaType.ONBOARDING)
                .failureType(FailureType.KYC_REJECTED)
                .userId(userId)
                .amount(null)
                .currency(null)
                .targetName(null)
                .technicalReasons(rejectionReasons != null
                        ? rejectionReasons : List.of())
                .fundsWereReserved(false)
                .fundsAreReleased(false)
                .canRetry(canRetry)
                .language(language)
                .build();
    }

    /**
     * A saga step timed out waiting for a participant reply.
     */
    public static SagaFailureContext timeout(
            String userId,
            BigDecimal amount,
            String currency,
            String targetName,
            boolean fundsWereReserved,
            String language) {

        return SagaFailureContext.builder()
                .sagaType(SagaType.TRANSFER)
                .failureType(FailureType.SAGA_TIMEOUT)
                .userId(userId)
                .amount(amount)
                .currency(currency)
                .targetName(targetName)
                .technicalReasons(List.of("Step timeout"))
                .fundsWereReserved(fundsWereReserved)
                .fundsAreReleased(false)
                .canRetry(true)
                .language(language)
                .build();
    }

    /**
     * Compensation itself failed — manual intervention required.
     */
    public static SagaFailureContext compensationFailed(
            String userId,
            BigDecimal amount,
            String currency,
            String language) {

        return SagaFailureContext.builder()
                .sagaType(SagaType.TRANSFER)
                .failureType(FailureType.COMPENSATION_FAILED)
                .userId(userId)
                .amount(amount)
                .currency(currency)
                .targetName(null)
                .technicalReasons(List.of("Compensation failed"))
                .fundsWereReserved(true)
                .fundsAreReleased(false)
                .canRetry(false)
                .language(language)
                .build();
    }
}