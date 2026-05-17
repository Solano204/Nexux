package com.nexus.identity.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * IdentityRejectedEvent — published when KYC is rejected.
 * Consumed by: nexus-notification-service (send rejection email),
 *              nexus-saga-orchestrator (handle rejection in saga).
 */
public record IdentityRejectedEvent(
        UUID userId,
        UUID verificationId,
        List<String> failureReasons,
        String userMessage,
        int attemptNumber,
        int attemptsRemaining,
        boolean isPermanent,
        Instant rejectedAt,
        String traceId
) {
    public static final String EVENT_TYPE = "IdentityRejected";
    public static final String AGGREGATE_TYPE = "USER";
}