package com.nexus.auth.lambda.model;

import java.time.Instant;
import java.util.List;

public record SessionRecord(
        String userId,
        String sessionId,
        String cognitoSub,
        String status,
        boolean kycVerified,
        String accountStatus,
        List<String> roles,
        Instant expiresAt,
        Instant lastKycSyncAt
) {}