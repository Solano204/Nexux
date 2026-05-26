package com.nexus.notification.domain.model.enums;

public enum NotificationEventType {
    TRANSACTION_COMPLETED,
    TRANSACTION_FAILED,
    FRAUD_ALERT,
    ACCOUNT_CREATED,
    KYC_APPROVED,
    KYC_REJECTED,
    ACCOUNT_FROZEN,
    ACCOUNT_CLOSED,
    SECURITY_ALERT,
    LOW_BALANCE_ALERT,
    WELCOME,
    SAGA_NOTIFICATION
}