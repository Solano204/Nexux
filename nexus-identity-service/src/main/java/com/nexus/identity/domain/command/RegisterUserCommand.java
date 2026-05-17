package com.nexus.identity.domain.command;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/**
 * RegisterUserCommand — initiates user registration.
 * Produced by AuthController, consumed by UserCommandService.
 */
public record RegisterUserCommand(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, max = 100) String password,
        @NotBlank @Size(min = 2, max = 200) String fullName,
        @NotBlank String phoneNumber,
        @NotNull LocalDate dateOfBirth,
        @NotBlank @Size(min = 2, max = 2) String country,
        String ipAddress,
        String userAgent,
        String traceId
) {}