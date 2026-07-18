package com.nexus.transaction.web.dto.request;

import com.nexus.transaction.domain.model.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record InitiateTransactionRequest(
        @Schema(description = "Client-generated dedup key. Retrying with the same key (for the same " +
                "caller) returns the original transaction instead of creating a duplicate — generate " +
                "a fresh UUID per genuinely new transfer/payment.",
                example = "3f9a2b1c-8d4e-4a6f-9c2d-1e5f7a8b9c0d", minLength = 8, maxLength = 64)
        @NotBlank @Size(min = 8, max = 64) String idempotencyKey,

        @Schema(description = "Account the money moves from — must belong to the caller (X-User-Id).",
                example = "11111111-1111-1111-1111-111111111111")
        @NotNull UUID sourceAccountId,

        @Schema(description = "Destination account UUID, for transfers between NEXUS accounts. " +
                "Mutually exclusive in practice with targetAccountNumber (external transfer).",
                example = "22222222-2222-2222-2222-222222222222")
        UUID targetAccountId,

        @Schema(description = "Destination account number, for external transfers where the target " +
                "isn't a NEXUS account UUID.", example = "ACC-9F8E7D6C")
        String targetAccountNumber,

        @Schema(description = "Owning user of the target account, when known — used for fraud's " +
                "account-relationship checks. Optional.")
        UUID targetUserId,

        @Schema(description = "Amount in the transaction's currency. Fees are calculated separately " +
                "and are not included here.", example = "500.00", minimum = "0.01", maximum = "99999999.9999")
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999.9999") BigDecimal amount,

        @Schema(description = "ISO 4217 currency code. Defaults to MXN if omitted.", example = "MXN")
        String currency,

        @Schema(description = "TRANSFER for account-to-account moves, PAYMENT for merchant payments — " +
                "must match which endpoint you're calling (/transfer vs /payment).", example = "TRANSFER")
        @NotNull TransactionType transactionType,

        @Schema(description = "Channel the transaction originated from. Defaults to API if omitted.",
                example = "MOBILE")
        TransactionChannel channel,

        @Schema(description = "Free-text description shown in the account's event/transaction history.",
                example = "Rent split - March", maxLength = 500)
        @Size(max = 500) String description,

        @Schema(description = "Merchant name, for PAYMENT transactions.", example = "Amazon MX", maxLength = 200)
        @Size(max = 200) String merchantName,

        @Schema(description = "4-digit ISO 18245 merchant category code, for PAYMENT transactions.",
                example = "5411", maxLength = 4)
        @Size(max = 4) String merchantCategoryCode,

        @Schema(description = "Caller-supplied reference number for reconciliation — not validated " +
                "against any external system, stored as-is.", maxLength = 100)
        @Size(max = 100) String referenceNumber
) {}