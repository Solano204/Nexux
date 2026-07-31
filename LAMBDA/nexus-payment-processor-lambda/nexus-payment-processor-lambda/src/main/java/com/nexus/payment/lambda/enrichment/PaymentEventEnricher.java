package com.nexus.payment.lambda.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.IncomingPaymentEvent;
import com.nexus.payment.lambda.model.NormalizedPaymentEvent;
import com.nexus.payment.lambda.model.enums.PaymentNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payment Event Enricher — transforms raw network event into
 * the canonical NormalizedPaymentEvent the Plane A expects.
 *
 * Enrichment steps:
 * 1. Generate stable eventId (UUID v4 from networkTransactionId seed)
 * 2. Resolve nexusAccountId from cardToken (hash lookup)
 * 3. Resolve merchantCategory from MCC code
 * 4. Normalize amount to MXN (apply exchange rate)
 * 5. Classify POS entry mode (chip/contactless/online)
 * 6. Set sourceService + traceId
 *
 * Card token → account resolution is mocked here.
 * In production: call nexus-account-service internal endpoint
 * or maintain a DynamoDB card-token → accountId mapping table.
 */
public class PaymentEventEnricher {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentEventEnricher.class);

    // MCC code → human-readable category
    private static final Map<String, String> MCC_CATEGORIES = Map.of(
            "5411", "GROCERY_STORES",
            "5812", "RESTAURANTS",
            "5541", "GAS_STATIONS",
            "5912", "PHARMACIES",
            "5311", "DEPARTMENT_STORES",
            "4111", "TRANSPORTATION",
            "7011", "HOTELS",
            "5999", "MISCELLANEOUS_RETAIL",
            "6011", "ATM_CASH",
            "4814", "TELECOMMUNICATIONS"
    );

    // Approximate exchange rates (production: call FX API)
    private static final Map<String, BigDecimal> FX_RATES_TO_MXN = Map.of(
            "MXN", BigDecimal.ONE,
            "USD", new BigDecimal("17.25"),
            "EUR", new BigDecimal("18.90"),
            "GBP", new BigDecimal("21.80"),
            "CAD", new BigDecimal("12.65")
    );

    private final ObjectMapper mapper;

    public PaymentEventEnricher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public NormalizedPaymentEvent enrich(
            IncomingPaymentEvent raw, String traceId) {

        // ── 1. Stable event ID ─────────────────────────────────
        // Deterministic: same network transaction always gets
        // the same eventId (UUID v3/v5 from name)
        String eventId = UUID.nameUUIDFromBytes(
                        (raw.network() + "#" +
                                raw.networkTransactionId() + "#" +
                                raw.eventType()).getBytes())
                .toString();

        // ── 2. Account resolution ──────────────────────────────
        // Production: DynamoDB lookup or account service call.
        // Here: derive from cardToken hash for consistency.
        String nexusAccountId = resolveAccountId(raw);
        String userId = resolveUserId(nexusAccountId);

        // ── 3. MCC → human category ───────────────────────────
        String merchantCategory = MCC_CATEGORIES.getOrDefault(
                raw.merchantCategoryCode(), "OTHER");

        // ── 4. Currency normalization ──────────────────────────
        BigDecimal exchangeRate = FX_RATES_TO_MXN.getOrDefault(
                raw.currency().toUpperCase(), BigDecimal.ONE);
        BigDecimal amountMxn = raw.amount()
                .multiply(exchangeRate)
                .setScale(2, RoundingMode.HALF_UP);

        // ── 5. POS entry mode classification ──────────────────
        boolean isContactless = raw.posEntryMode() != null &&
                (raw.posEntryMode().startsWith("07") ||
                        raw.posEntryMode().startsWith("91"));
        boolean isChip = raw.posEntryMode() != null &&
                raw.posEntryMode().startsWith("05");
        boolean isOnline = !raw.cardholderPresent();

        log.debug("Enriched: networkTxnId={} account={} " +
                        "amountMxn={}",
                raw.networkTransactionId(), nexusAccountId, amountMxn);

        return new NormalizedPaymentEvent(
                eventId,
                raw.networkTransactionId(),
                PaymentNetwork.from(raw.network()).name(),
                raw.eventType().toUpperCase(),
                nexusAccountId,
                userId,
                raw.merchantId(),
                raw.merchantName(),
                raw.merchantCategoryCode(),
                merchantCategory,
                raw.amount(),
                amountMxn,
                raw.currency().toUpperCase(),
                raw.currency().toUpperCase(),
                exchangeRate,
                raw.authorizationCode(),
                raw.responseCode(),
                raw.isApproved(),
                raw.declineReason(),
                raw.terminalId(),
                raw.posEntryMode(),
                raw.cardholderPresent(),
                isChip || isContactless,
                isContactless,
                isOnline,
                raw.networkTimestamp() != null
                        ? raw.networkTimestamp() : Instant.now(),
                Instant.now(),
                "nexus-payment-processor-lambda",
                traceId
        );
    }

    private String resolveAccountId(IncomingPaymentEvent raw) {
        // Production: DynamoDB card-token-to-account table lookup.
        // For the platform build: deterministic mock.
        String token = raw.cardToken() != null
                ? raw.cardToken() : raw.cardPan();
        if (token == null) return "UNRESOLVED";

        // Deterministic: same token always → same accountId
        // String.format("%08X", ...) zero-pads to a fixed 8 hex digits —
        // Integer.toHexString() does not, and its output can be as short
        // as 1 char, which crashed a subsequent substring(0, 8).
        return "ACC-" + String.format(
                "%08X", Math.abs(token.hashCode()));
    }

    private String resolveUserId(String accountId) {
        if ("UNRESOLVED".equals(accountId)) return "UNKNOWN";
        return "USR-" + String.format(
                "%08X", Math.abs(accountId.hashCode()));
    }
}