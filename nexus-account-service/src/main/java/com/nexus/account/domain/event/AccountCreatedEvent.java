package com.nexus.account.domain.event;

import com.nexus.account.domain.model.enums.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published via outbox when a new account is created. */
public record AccountCreatedEvent(
        UUID accountId,
        String accountNumber,
        UUID userId,
        AccountType accountType,
        String currency,
        BigDecimal initialBalance,
        Instant createdAt,
        String traceId
) {
    public static final String EVENT_TYPE = "AccountCreated";
    public static final String AGGREGATE_TYPE = "ACCOUNT";
}