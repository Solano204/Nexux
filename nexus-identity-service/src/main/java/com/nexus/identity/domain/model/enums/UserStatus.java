package com.nexus.identity.domain.model.enums;

/**
 * User account lifecycle status.
 * CRITICAL: Gateway reads this from JWT claims on every request.
 * Changes here must be coordinated with gateway configuration.
 */
public enum UserStatus {
    /** User registered but KYC not yet initiated */
    PENDING_KYC,
    /** KYC document submitted, analysis in progress */
    KYC_IN_PROGRESS,
    /** KYC approved, account fully functional */
    ACTIVE,
    /** Account suspended (admin action or failed login lockout) */
    SUSPENDED,
    /** KYC rejected, retries available */
    KYC_REJECTED,
    /** KYC rejected after maximum 3 attempts — permanent */
    KYC_REJECTED_PERMANENT,
    /** Registration was cancelled by SAGA compensation */
    REGISTRATION_CANCELLED,
    /** Account closed by user or compliance */
    CLOSED
}