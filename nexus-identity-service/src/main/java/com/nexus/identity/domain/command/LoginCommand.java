package com.nexus.identity.domain.command;

/**
 * LoginCommand — authenticates a user by email + password.
 */
public record LoginCommand(
        String email,
        String password,
        String deviceFingerprint,
        String ipAddress,
        String userAgent,
        String traceId
) {}