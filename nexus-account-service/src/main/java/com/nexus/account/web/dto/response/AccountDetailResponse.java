package com.nexus.account.web.dto.response;

import java.math.BigDecimal;

/**
 * Detailed account view including limits and lifecycle info.
 * Used by: GET /api/v1/accounts/{id}
 */
public record AccountDetailResponse(
        String accountId,
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal availableBalance,
        BigDecimal reservedAmount,
        BigDecimal totalBalance,
        String status,
        BigDecimal dailyTransactionLimit,
        BigDecimal dailyTransactionUsed,
        BigDecimal dailyLimitRemaining,
        BigDecimal monthlyTransactionLimit,
        BigDecimal monthlyTransactionUsed,
        BigDecimal minimumBalance,
        BigDecimal interestRate,
        String createdAt,
        String frozenAt,
        String frozenReason
) {}