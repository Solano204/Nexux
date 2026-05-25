package com.nexus.kyc.domain.model;

import com.nexus.kyc.domain.model.enums.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * KYC Verification Request — submitted by user via API.
 * Image is submitted as a separate multipart file.
 */
public record KycVerificationRequest(
        @NotBlank String userId,
        @NotBlank String fullName,
        @NotBlank String dateOfBirth,    // ISO format: YYYY-MM-DD
        @NotBlank String documentNumber,
        @NotNull  DocumentType documentType,
        String nationality,              // Optional
        String language                  // For rejection messages: "es", "en"
) {}