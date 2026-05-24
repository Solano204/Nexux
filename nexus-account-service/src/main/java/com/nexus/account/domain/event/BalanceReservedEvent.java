package com.nexus.account.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Emitted by Account.reserve(). Captured in domainEvents list. */
public record BalanceReservedEvent(
        UUID accountId,
        String transactionId,
        BigDecimal reservedAmount,
        BigDecimal availableBefore,
        BigDecimal availableAfter,
        BigDecimal reservedBefore,
        BigDecimal reservedAfter
) {
    public static final String EVENT_TYPE = "BalanceReserved";
}