package com.nexus.reporting.model;

import java.math.BigDecimal;
import java.util.List;

public record UserStatementData(
    String userId,
    String date,
    BigDecimal totalSpent,
    BigDecimal totalReceived,
    int transactionCount,
    String currency,
    List<StatementTransaction> transactions
) {
    public record StatementTransaction(
        String transactionId,
        String transactionType,
        BigDecimal amount,
        String currency,
        String description,
        String category,
        String completedAt,
        String direction
    ) {}
}
