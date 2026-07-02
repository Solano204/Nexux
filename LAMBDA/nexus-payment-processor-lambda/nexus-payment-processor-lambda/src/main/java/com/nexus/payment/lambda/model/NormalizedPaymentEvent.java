package com.nexus.payment.lambda.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * NormalizedPaymentEvent — card-network-agnostic internal event.
 * Written to Kafka topic "transactions.initiated" on Plane A.
 *
 * Produced by PaymentEventEnricher after translating from
 * IncomingPaymentEvent (external format) to this canonical form.
 * All amounts normalized to MXN. MCC resolved to human-readable category.
 * nexusAccountId and userId resolved from card token lookup.
 */
public record NormalizedPaymentEvent(
        String eventId,                // UUID v7 — stable idempotency key
        String networkTransactionId,
        String network,
        String eventType,
        String nexusAccountId,         // resolved from cardToken/cardPan
        String userId,                 // resolved from nexusAccountId
        String merchantId,
        String merchantName,
        String merchantCategoryCode,
        String merchantCategory,       // human-readable from MCC
        BigDecimal amount,
        BigDecimal amountMxn,          // normalized to MXN
        String currency,
        String originalCurrency,
        BigDecimal exchangeRate,
        String authorizationCode,
        String responseCode,
        boolean isApproved,
        String declineReason,
        String terminalId,
        String posEntryMode,
        boolean cardholderPresent,
        boolean isCardPresent,
        boolean isContactless,
        boolean isOnline,
        Instant networkTimestamp,
        Instant receivedAt,
        String sourceService,          // nexus-payment-processor-lambda
        String traceId
) {}
