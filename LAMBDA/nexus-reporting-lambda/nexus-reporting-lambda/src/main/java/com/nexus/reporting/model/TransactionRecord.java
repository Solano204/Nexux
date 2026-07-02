package com.nexus.reporting.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionRecord(
    String transactionId,
    String userId,
    String sourceAccountId,
    String targetAccountId,
    BigDecimal amount,
    String currency,
    String transactionType,
    String status,
    String paymentNetwork,
    String merchantId,
    String merchantName,
    String merchantCategoryCode,
    String category,
    BigDecimal fraudScore,
    String fraudDecision,
    String createdAt,
    String completedAt,
    Long processingDurationMs,
    String sourceIp,
    String description
) {
    public boolean isDebit() {
        return transactionType != null && switch (transactionType) {
            case "PAYMENT", "EXTERNAL_TRANSFER", "DIGITAL_PAYMENT",
                 "INTERNAL_TRANSFER", "FEE" -> true;
            default -> false;
        };
    }

    public boolean isCredit() {
        return transactionType != null && switch (transactionType) {
            case "CREDIT", "REFUND", "INCOME_CREDIT", "INTEREST" -> true;
            default -> false;
        };
    }
}
