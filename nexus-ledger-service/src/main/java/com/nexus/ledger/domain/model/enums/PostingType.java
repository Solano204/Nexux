package com.nexus.ledger.domain.model.enums;

public enum PostingType {
    TRANSFER,
    PAYMENT,
    FEE,
    INTEREST_PAYMENT,
    INTEREST_ACCRUAL,
    REVERSAL,
    INITIAL_BALANCE,
    ADJUSTMENT,
    REGULATORY_HOLD,
    DIRECT_DEPOSIT,
    CASH_IN
}