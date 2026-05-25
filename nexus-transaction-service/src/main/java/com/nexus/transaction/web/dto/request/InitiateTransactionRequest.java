package com.nexus.transaction.web.dto.request;

import com.nexus.transaction.domain.model.enums.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record InitiateTransactionRequest(
        @NotBlank @Size(min = 8, max = 64) String idempotencyKey,
        @NotNull UUID sourceAccountId,
        UUID targetAccountId,
        String targetAccountNumber,
        UUID targetUserId,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999.9999") BigDecimal amount,
        String currency,
        @NotNull TransactionType transactionType,
        TransactionChannel channel,
        @Size(max = 500) String description,
        @Size(max = 200) String merchantName,
        @Size(max = 4) String merchantCategoryCode,
        @Size(max = 100) String referenceNumber
) {}