package com.nexus.identity.web.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, max = 100) String password,
        @NotBlank @Size(min = 2, max = 200) String fullName,
        @NotBlank @Pattern(regexp = "\\+[1-9]\\d{6,14}") String phoneNumber,
        @NotNull @Past LocalDate dateOfBirth,
        @NotBlank @Size(min = 2, max = 2) String country
) {}