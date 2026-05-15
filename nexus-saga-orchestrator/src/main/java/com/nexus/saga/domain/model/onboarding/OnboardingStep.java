package com.nexus.saga.domain.model.onboarding;

public enum OnboardingStep {
    STARTED,
    KYC_INITIATED,
    KYC_IN_PROGRESS,
    KYC_APPROVED,
    ACCOUNTS_CREATING,
    ACCOUNTS_CREATED,
    WELCOME_NOTIFICATION_SENT,
    COMPLETED,                  // terminal — success

    KYC_REJECTED,
    KYC_TIMEOUT,
    ACCOUNT_CREATION_FAILED,
    COMPENSATING_REGISTRATION,
    REGISTRATION_CANCELLED,     // terminal — clean failure
    PERMANENTLY_FAILED;         // terminal — compensation failed

    public boolean isTerminal() {
        return this == COMPLETED
                || this == REGISTRATION_CANCELLED
                || this == PERMANENTLY_FAILED;
    }
}