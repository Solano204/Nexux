package com.nexus.account.web.dto.response;

import java.math.BigDecimal;

/**
 * Response returned when accounts are created.
 * Used by: POST /internal/api/v1/accounts/create-defaults
 * Consumer: nexus-identity-service (OnboardingFlowSaga Step 3)
 */
public record AccountCreatedResponse(
        String accountId,
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal initialBalance,
        String status
) {}