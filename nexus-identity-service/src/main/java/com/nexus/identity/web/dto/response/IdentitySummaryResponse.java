package com.nexus.identity.web.dto.response;

import java.util.List;

public record IdentitySummaryResponse(
        String userId,
        String email,
        String fullName,
        String status,
        boolean kycVerified,
        java.util.List<String> roles
) {}