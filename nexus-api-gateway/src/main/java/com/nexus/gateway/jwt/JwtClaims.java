package com.nexus.gateway.jwt;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Immutable value object carrying validated JWT claims.
 * Only created after full validation — never from raw token data.
 */
@Builder
public record JwtClaims(
        String userId,
        String jti,
        List<String> roles,
        String accountStatus,
        Instant expiresAt,
        Instant issuedAt
) {
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAccountActive() {
        return "ACTIVE".equals(accountStatus);
    }

    public boolean isAccountSuspended() {
        return "SUSPENDED".equals(accountStatus);
    }
}