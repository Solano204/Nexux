package com.nexus.identity.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * IdentityVerifiedEvent — published when KYC is approved.
 * Consumed by: nexus-saga-orchestrator (advance OnboardingFlowSaga),
 *              nexus-account-service (create initial account),
 *              nexus-notification-service (send KYC approved email).
 */
public record IdentityVerifiedEvent(
        UUID userId,
        UUID verificationId,
        String documentType,
        Instant verifiedAt,
        String traceId
) {
    public static final String EVENT_TYPE = "IdentityVerified";
    public static final String AGGREGATE_TYPE = "USER";
}