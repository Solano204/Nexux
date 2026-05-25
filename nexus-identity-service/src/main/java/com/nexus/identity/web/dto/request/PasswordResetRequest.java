package com.nexus.identity.web.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record PasswordResetRequest(@NotBlank @Email String email) {}
