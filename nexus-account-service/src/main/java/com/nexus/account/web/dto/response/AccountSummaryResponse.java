package com.nexus.account.web.dto.response;

import java.math.BigDecimal;

/**
 * Summary view of an account — used for listing all user accounts.
 * Used by: GET /api/v1/accounts
 */
public record AccountSummaryResponse(
        String accountId,
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal availableBalance,
        BigDecimal reservedAmount,
        BigDecimal totalBalance,
        String status,
        String createdAt
) {}