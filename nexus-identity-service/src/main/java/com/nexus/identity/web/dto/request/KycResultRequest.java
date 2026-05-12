package com.nexus.identity.web.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record KycResultRequest(
        String verificationId,
        boolean approved,
        java.util.Map<String, Object> extractedData,
        java.util.Map<String, Object> verificationDecision,
        java.util.List<String> failureReasons
) {}