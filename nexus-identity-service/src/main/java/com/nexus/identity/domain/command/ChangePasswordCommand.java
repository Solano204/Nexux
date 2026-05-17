package com.nexus.identity.domain.command;

import java.util.UUID;

/**
 * ChangePasswordCommand — replaces user password after verification.
 * Triggers invalidation of all OTHER active sessions.
 */
public record ChangePasswordCommand(
        UUID userId,
        String currentPassword,
        String newPassword,
        UUID currentSessionId,
        String ipAddress,
        String traceId
) {}