package com.nexus.auth.lambda.model;

import java.time.Instant;
import java.util.List;

public record TokenRefreshResult(
        String accessToken,
        String idToken,
        int expiresIn,
        String tokenType
) {}