
package com.nexus.transaction.web.dto.response;

import java.math.BigDecimal;

public record TransactionResponse(
        String transactionId,
        String status,
        BigDecimal amount,
        String currency,
        String transactionType,
        String description,
        String initiatedAt,
        String completedAt,
        String failureReason
) {}