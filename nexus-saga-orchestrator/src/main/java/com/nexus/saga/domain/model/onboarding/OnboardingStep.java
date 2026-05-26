package com.nexus.saga.domain.model.onboarding;

/**
 * OnboardingStep — State machine for the OnboardingFlowSaga.
 *
 * Happy path:
 * STARTED → KYC_INITIATED → KYC_IN_PROGRESS → KYC_APPROVED →
 * ACCOUNTS_CREATING → ACCOUNTS_CREATED →
 * WELCOME_NOTIFICATION_SENT → COMPLETED
 *
 * Failure paths all lead to REGISTRATION_CANCELLED or PERMANENTLY_FAILED.
 */
public enum OnboardingStep {

    // ── Happy path ────────────────────────────────────────────
    STARTED,
    KYC_INITIATED,
    KYC_IN_PROGRESS,
    AWAITING_DOCUMENT,
    AWAITING_RETRY,
    KYC_APPROVED,
    ACCOUNTS_CREATING,
    ACCOUNTS_CREATED,
    WELCOME_NOTIFICATION_SENT,
    COMPLETED,                   // terminal — success

    // ── Failure + compensation paths ──────────────────────────
    KYC_REJECTED,
    KYC_TIMEOUT,
    ACCOUNT_CREATION_FAILED,
    AWAITING_MANUAL_REVIEW,
    COMPENSATING_REGISTRATION,
    REGISTRATION_CANCELLED,      // terminal — clean failure
    COMPENSATION_COMPLETED,      // terminal — retry possible

    // ── Last-resort terminal ──────────────────────────────────
    PERMANENTLY_FAILED;          // terminal — manual intervention needed

    public boolean isTerminal() {
        return this == COMPLETED
                || this == REGISTRATION_CANCELLED
                || this == COMPENSATION_COMPLETED
                || this == PERMANENTLY_FAILED;
    }

    /**
     * Returns true if this step can transition to the given next step.
     * Used as a guard before any state transition to prevent illegal moves.
     */
    public boolean canTransitionTo(OnboardingStep next) {
        return switch (this) {
            case STARTED ->
                    next == KYC_INITIATED || next == AWAITING_DOCUMENT;
            case AWAITING_DOCUMENT ->
                    next == KYC_INITIATED;
            case KYC_INITIATED ->
                    next == KYC_IN_PROGRESS || next == KYC_REJECTED
                            || next == KYC_TIMEOUT;
            case KYC_IN_PROGRESS ->
                    next == KYC_APPROVED || next == KYC_REJECTED
                            || next == AWAITING_MANUAL_REVIEW || next == KYC_TIMEOUT;
            case AWAITING_MANUAL_REVIEW ->
                    next == KYC_APPROVED || next == KYC_REJECTED;
            case KYC_APPROVED ->
                    next == ACCOUNTS_CREATING;
            case ACCOUNTS_CREATING ->
                    next == ACCOUNTS_CREATED || next == ACCOUNT_CREATION_FAILED;
            case ACCOUNTS_CREATED ->
                    next == WELCOME_NOTIFICATION_SENT || next == COMPLETED;
            case WELCOME_NOTIFICATION_SENT ->
                    next == COMPLETED;
            case KYC_REJECTED ->
                    next == AWAITING_RETRY || next == COMPENSATING_REGISTRATION;
            case AWAITING_RETRY ->
                    next == KYC_INITIATED;
            case KYC_TIMEOUT, ACCOUNT_CREATION_FAILED ->
                    next == COMPENSATING_REGISTRATION;
            case COMPENSATING_REGISTRATION ->
                    next == REGISTRATION_CANCELLED
                            || next == COMPENSATION_COMPLETED
                            || next == PERMANENTLY_FAILED;
            default -> false;
        };
    }

    /**
     * Returns true if this step is after the given step in the happy path.
     * Used to determine what needs to be compensated.
     */
    public boolean isAfter(OnboardingStep other) {
        return ordinal() > other.ordinal();
    }
}