package com.nexus.identity.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * LoginSuccessfulEvent — published on each successful login.
 * Consumed by: nexus-fraud-service (behavioral analysis),
 *              nexus-risk-scoring-service (update user risk profile).
 */
public record LoginSuccessfulEvent(
        UUID userId,
        UUID sessionId,
        String ipAddress,
        String deviceFingerprint,
        String userAgent,
        Instant loginAt,
        String traceId
) {
    public static final String EVENT_TYPE = "LoginSuccessful";
    public static final String AGGREGATE_TYPE = "USER";
}