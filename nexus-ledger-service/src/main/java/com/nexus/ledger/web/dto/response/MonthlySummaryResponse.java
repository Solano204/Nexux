package com.nexus.ledger.web.dto.response;

import java.math.BigDecimal;

public record MonthlySummaryResponse(
        int year,
        int month,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        BigDecimal netChange,
        int transactionCount
) {}