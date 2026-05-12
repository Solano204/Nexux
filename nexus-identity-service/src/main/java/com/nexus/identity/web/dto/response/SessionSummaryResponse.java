package com.nexus.identity.web.dto.response;

import java.util.List;

public record SessionSummaryResponse(
        String sessionId,
        String ipAddress,
        String deviceFingerprint,
        String issuedAt,
        String lastActivityAt
) {}
