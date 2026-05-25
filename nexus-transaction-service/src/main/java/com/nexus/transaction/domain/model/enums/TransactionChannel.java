package com.nexus.transaction.domain.model.enums;

/**
 * Channel through which a transaction was initiated.
 */
public enum TransactionChannel {
    API,
    MOBILE,
    WEB,
    ATPM,
    BRANCH,
    BATCH
}