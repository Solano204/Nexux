package com.nexus.ledger.web.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record CategoryBreakdownResponse(
        String accountId,
        String startDate,
        String endDate,
        List<CategoryEntry> categories
) {
    public record CategoryEntry(
            String category,
            String entryType,
            BigDecimal total,
            int count
    ) {}
}