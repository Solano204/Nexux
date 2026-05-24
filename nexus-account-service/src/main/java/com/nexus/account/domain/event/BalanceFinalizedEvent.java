package com.nexus.account.domain.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emitted by Account.finalizeDebit() and Account.credit().
 * direction: "DEBIT" or "CREDIT"
 */
public record BalanceFinalizedEvent(
        UUID accountId,
        String transactionId,
        BigDecimal amount,
        BigDecimal availableBefore,
        BigDecimal availableAfter,
        BigDecimal reservedBefore,
        BigDecimal reservedAfter,
        String direction
) {
    // Constructor used by finalizeDebit (no availableBefore separate param)
    public BalanceFinalizedEvent(UUID accountId, String transactionId,
                                 BigDecimal amount,
                                 BigDecimal availableBalance,
                                 BigDecimal reservedBefore,
                                 BigDecimal reservedAfter,
                                 String direction) {
        this(accountId, transactionId, amount,
                availableBalance, availableBalance,
                reservedBefore, reservedAfter, direction);
    }
    public static final String EVENT_TYPE = "BalanceFinalized";
}