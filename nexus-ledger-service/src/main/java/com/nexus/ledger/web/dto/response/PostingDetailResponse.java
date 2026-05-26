package com.nexus.ledger.web.dto.response;

import java.math.BigDecimal;

public record PostingDetailResponse(
        String postingId,
        String transactionId,
        String postingType,
        String description,
        BigDecimal amount,
        String currency,
        String postedAt
) {}