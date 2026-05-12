package com.nexus.identity.web.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;



public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        String deviceFingerprint
) {}
