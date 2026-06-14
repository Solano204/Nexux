package com.nexus.auth.lambda.model;
import java.time.Instant;
import java.util.List;
public record TokenValidationResult(
        boolean valid,
        String userId,
        List<String> roles,
        String accountStatus,
        boolean kycVerified,
        String sessionId,
        Instant expiresAt
) {}