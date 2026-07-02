package com.nexus.payment.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * IncomingPaymentEvent — raw event from card network webhook.
 *
 * Card networks (Visa, Mastercard, AMEX) each have slightly
 * different schemas. This model uses @JsonIgnoreProperties(ignoreUnknown=true)
 * to tolerate unknown fields, with the enricher normalizing
 * differences between network schemas.
 *
 * Idempotency key: networkTransactionId + network combination.
 * Same transaction from the same network is always the same event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingPaymentEvent(

        @JsonProperty("networkTransactionId")
        @NotBlank(message = "networkTransactionId required")
        String networkTransactionId,

        @JsonProperty("network")
        @NotBlank(message = "network required")
        String network,                        // VISA, MASTERCARD, AMEX, SPEI

        @JsonProperty("eventType")
        @NotBlank(message = "eventType required")
        String eventType,                      // AUTHORIZATION, CAPTURE, REVERSAL, REFUND

        @JsonProperty("cardPan")
        String cardPan,                        // masked: 411111XXXXXX1111

        @JsonProperty("cardToken")
        String cardToken,                      // network-issued token (preferred)

        @JsonProperty("merchantId")
        String merchantId,

        @JsonProperty("merchantName")
        String merchantName,

        @JsonProperty("merchantCategoryCode")
        String merchantCategoryCode,           // MCC code e.g. "5411"

        @JsonProperty("amount")
        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal amount,

        @JsonProperty("currency")
        @NotBlank
        @Size(min = 3, max = 3)
        String currency,                       // ISO 4217: MXN, USD, EUR

        @JsonProperty("authorizationCode")
        String authorizationCode,

        @JsonProperty("responseCode")
        String responseCode,                   // 00=approved, 05=declined...

        @JsonProperty("isApproved")
        boolean isApproved,

        @JsonProperty("declineReason")
        String declineReason,

        @JsonProperty("terminalId")
        String terminalId,

        @JsonProperty("acquirerId")
        String acquirerId,

        @JsonProperty("networkTimestamp")
        Instant networkTimestamp,

        @JsonProperty("localTransactionTime")
        String localTransactionTime,           // HHMMSS from terminal

        @JsonProperty("localTransactionDate")
        String localTransactionDate,           // MMDD from terminal

        @JsonProperty("posEntryMode")
        String posEntryMode,                   // 051=chip, 071=contactless

        @JsonProperty("cardholderPresent")
        boolean cardholderPresent,

        @JsonProperty("metadata")
        java.util.Map<String, String> metadata // network-specific extras
) {}
