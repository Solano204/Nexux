package com.nexus.identity.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * UserRegisteredEvent — published to outbox on successful registration.
 * Debezium reads from outbox → Kafka users.registered topic.
 * Consumed by: nexus-saga-orchestrator (start OnboardingFlowSaga),
 *              nexus-notification-service (send welcome email).
 */
public record UserRegisteredEvent(
        UUID userId,
        String email,
        String fullName,
        String phoneNumber,
        String country,
        Instant registeredAt,
        String traceId
) {
    public static final String EVENT_TYPE = "UserRegistered";
    public static final String AGGREGATE_TYPE = "USER";
}