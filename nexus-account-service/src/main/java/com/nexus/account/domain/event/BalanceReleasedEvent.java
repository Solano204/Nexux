package com.nexus.account.domain.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Emitted by Account.release(). Signals SAGA compensation completed. */
public record BalanceReleasedEvent(
        UUID accountId,
        String transactionId,
        BigDecimal releasedAmount,
        BigDecimal availableBefore,
        BigDecimal availableAfter,
        BigDecimal reservedBefore,
        BigDecimal reservedAfter
) {
    public static final String EVENT_TYPE = "BalanceReleased";
}