package com.nexus.account.web.dto.response;

import java.math.BigDecimal;

/**
 * Account event view for audit trail display.
 * Used by: GET /api/v1/accounts/{id}/events
 */
public record AccountEventResponse(
        String eventId,
        String eventType,
        BigDecimal amount,
        BigDecimal availableBefore,
        BigDecimal availableAfter,
        String occurredAt
) {}