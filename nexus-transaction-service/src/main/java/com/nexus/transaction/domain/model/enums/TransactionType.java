package com.nexus.transaction.domain.model.enums;

/**
 * Types of financial transactions supported by the platform.
 */
public enum TransactionType {
    INTERNAL_TRANSFER,
    EXTERNAL_TRANSFER,
    PAYMENT,
    DIRECT_DEPOSIT,
    CASH_IN,
    CASH_OUT,
    FEE,
    INTEREST,
    REVERSAL
}