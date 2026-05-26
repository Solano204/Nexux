package com.nexus.account.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted when an account is frozen by compliance/fraud.
 * Consumed by: notification-dispatcher-lambda (alert user),
 *              fraud-alert-lambda (update risk score)
 */
public record AccountFrozenEvent(
        UUID accountId,
        String reason,
        Instant frozenAt
) {
    public AccountFrozenEvent(UUID accountId, String reason) {
        this(accountId, reason, Instant.now());
    }
}