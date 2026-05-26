package com.nexus.ledger.web.dto.response;

import java.math.BigDecimal;

public record LedgerEntryResponse(
        String entryId,
        String postingId,
        String entryType,
        BigDecimal amount,
        String currency,
        BigDecimal runningBalance,
        String description,
        String category,
        String merchantName,
        String postedAt
) {}