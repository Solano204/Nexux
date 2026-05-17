package com.nexus.identity.web.dto.response;

import java.util.List;

public record KycStatusResponse(
        String verificationId,
        String decision,
        String documentType,
        java.util.List<String> failureReasons,
        String initiatedAt
) {}
