package com.nexus.identity.web.dto.response;

import java.util.List;

public record UserProfileResponse(
        String userId,
        String email,
        String phoneNumber,
        String fullName,
        String dateOfBirth,
        String status,
        List<String> roles,
        boolean kycVerified,
        String kycVerifiedAt,
        String createdAt
) {}
