package com.nexus.identity.web.dto.response;

import java.util.List;


public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType,
        String userId,
        List<String> roles
) {}
