package com.nexus.identity.domain.model.enums;

/**
 * AuditEventType — all security-relevant events logged to audit_log.
 * Immutable once logged (trigger prevents UPDATE/DELETE on audit_log).
 */
public enum AuditEventType {

    // Registration lifecycle
    USER_REGISTERED,
    REGISTRATION_CANCELLED,

    // Authentication
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    LOGOUT,
    TOKEN_REFRESHED,
    TOKEN_REVOKED,

    // Password management
    PASSWORD_CHANGED,
    PASSWORD_CHANGE_FAILED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,

    // KYC lifecycle
    KYC_INITIATED,
    KYC_APPROVED,
    KYC_REJECTED,
    KYC_MANUAL_REVIEW,

    // Account lifecycle
    ACCOUNT_SUSPENDED,
    ACCOUNT_REACTIVATED,
    ACCOUNT_CLOSED,

    // Session management
    SESSION_TERMINATED,
    ALL_SESSIONS_REVOKED,

    // Admin actions
    ADMIN_STATUS_CHANGE,
    ADMIN_ROLE_CHANGE
}