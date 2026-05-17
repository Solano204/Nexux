package com.nexus.identity.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * UserSuspendedEvent — published when account is suspended.
 * Consumed by: nexus-notification-service (alert user and compliance),
 *              nexus-fraud-service (flag for review).
 */
public record UserSuspendedEvent(
        UUID userId,
        String reason,
        String suspendedBy,
        Instant suspendedAt,
        Instant lockUntil,
        String traceId
) {
    public static final String EVENT_TYPE = "UserSuspended";
    public static final String AGGREGATE_TYPE = "USER";
}