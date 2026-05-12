package com.nexus.identity.web.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;



public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 12, max = 100) String newPassword
) {}
