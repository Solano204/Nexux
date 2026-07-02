package com.nexus.kyc.web.controller.dto.response;

public record StatusResponse(
        String verificationId,
        String status,
        String submittedAt,
        String decidedAt,
        String userFacingMessage,
        boolean canRetry,
        boolean requiresAction
) {}