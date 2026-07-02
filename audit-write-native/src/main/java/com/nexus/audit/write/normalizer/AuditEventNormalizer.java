package com.nexus.audit.write.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.audit.write.model.AuditEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Audit Event Normalizer — transforms raw Kafka events into AuditEvent.
 *
 * Every service publishes its own event schema.
 * This class translates all of them into the canonical AuditEvent.
 *
 * Key design decisions:
 * - null-safe everywhere — a malformed event must not crash the consumer
 * - Always extracts eventId from the source event for idempotency
 * - Determines compliance flags from event content
 */
@ApplicationScoped
@RegisterForReflection
public class AuditEventNormalizer {

    @Inject
    ObjectMapper objectMapper;

    private static final Logger log =
            Logger.getLogger(AuditEventNormalizer.class);

    private static final BigDecimal LARGE_AMOUNT_THRESHOLD =
            new BigDecimal("10000");

    public AuditEvent normalize(JsonNode rawEvent,
                                String topic,
                                long kafkaOffset) {
        try {
            String eventType = detectEventType(rawEvent, topic);
            String category = categorize(eventType);
            String userId = extractUserId(rawEvent);
            Map<String, Object> payload = extractPayload(rawEvent);
            String severity = classifySeverity(eventType, rawEvent);
            String eventId = extractOrGenerateEventId(rawEvent);

            return new AuditEvent(
                    eventId,
                    eventType,
                    category,
                    severity,
                    userId,
                    rawEvent.path("sessionId").asText(null),
                    rawEvent.path("ipAddress").asText(null),
                    detectResourceType(eventType),
                    extractResourceId(rawEvent, eventType),
                    userId,
                    payload,
                    detectSourceService(topic),
                    rawEvent.path("traceId").asText(null),
                    rawEvent.path("spanId").asText(null),
                    topic,
                    kafkaOffset,
                    extractTimestamp(rawEvent).truncatedTo(ChronoUnit.MILLIS),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    isFinancialEvent(eventType),
                    isSensitiveData(eventType),
                    requiresSarReview(eventType, rawEvent),
                    isRegulatoryRequired(eventType)
            );

        } catch (Exception e) {
            log.errorf("Normalization failed for topic %s: %s",
                    topic, e.getMessage());
            return buildFallbackEvent(rawEvent, topic, kafkaOffset);
        }
    }

    private String detectEventType(JsonNode raw, String topic) {
        if (raw.has("eventType"))
            return raw.path("eventType").asText();
        if (raw.has("replyType"))
            return raw.path("replyType").asText();
        if (raw.has("commandType"))
            return raw.path("commandType").asText();

        return switch (topic) {
            case "transactions.completed" -> "TRANSACTION_COMPLETED";
            case "transactions.failed"    -> "TRANSACTION_FAILED";
            case "transactions.initiated" -> "TRANSACTION_INITIATED";
            case "fraud.result"           -> "FRAUD_DECISION";
            case "fraud.flagged"          -> "FRAUD_HIGH_SEVERITY";
            case "accounts.created"       -> "ACCOUNT_CREATED";
            case "account.frozen"         -> "ACCOUNT_FROZEN";
            case "users.registered"       -> "USER_REGISTERED";
            case "identity.verified"      -> "KYC_APPROVED";
            case "identity.rejected"      -> "KYC_REJECTED";
            case "ledger.posted"          -> "LEDGER_POSTED";
            case "ledger.reversed"        -> "LEDGER_REVERSED";
            case "saga.commands"          -> "SAGA_COMMAND";
            case "saga.replies"           -> "SAGA_REPLY";
            case "saga.completed"         -> "SAGA_COMPLETED";
            case "ai.query.logged"        -> "AI_QUERY";
            default -> "UNKNOWN_EVENT_" + topic.toUpperCase()
                    .replace(".", "_");
        };
    }

    private String categorize(String eventType) {
        if (eventType.contains("TRANSACTION") ||
                eventType.contains("LEDGER") ||
                eventType.contains("BALANCE") ||
                eventType.contains("TRANSFER"))
            return "FINANCIAL";

        if (eventType.contains("FRAUD") ||
                eventType.contains("LOGIN") ||
                eventType.contains("FROZEN") ||
                eventType.contains("SECURITY"))
            return "SECURITY";

        if (eventType.contains("KYC") ||
                eventType.contains("SAR") ||
                eventType.contains("COMPLIANCE"))
            return "COMPLIANCE";

        if (eventType.contains("CONFIG") ||
                eventType.contains("SYSTEM") ||
                eventType.contains("SAGA"))
            return "SYSTEM";

        if (eventType.contains("AI") ||
                eventType.contains("QUERY"))
            return "AI";

        return "GENERAL";
    }

    private String classifySeverity(String eventType, JsonNode raw) {
        if (eventType.contains("FRAUD_HIGH") ||
                eventType.contains("FROZEN") ||
                eventType.contains("DOCUMENT_FRAUD"))
            return "CRITICAL";

        if (eventType.contains("FRAUD") ||
                eventType.contains("REJECTED") ||
                eventType.contains("FAILED"))
            return "WARNING";

        double fraudScore = raw.path("payload")
                .path("fraudScore").asDouble(-1);
        if (fraudScore > 85) return "CRITICAL";
        if (fraudScore > 60) return "WARNING";

        String amountStr = raw.path("amount").asText(
                raw.path("payload").path("amount").asText("0"));
        try {
            BigDecimal amount = new BigDecimal(amountStr);
            if (amount.compareTo(LARGE_AMOUNT_THRESHOLD) > 0)
                return "WARNING";
        } catch (NumberFormatException ignored) {}

        return "INFO";
    }

    private String extractUserId(JsonNode raw) {
        for (String field : List.of("userId", "sourceUserId",
                "user_id", "subject")) {
            if (!raw.path(field).isMissingNode()) {
                return raw.path(field).asText(null);
            }
        }
        return raw.path("payload").path("userId").asText(null);
    }

    private String extractOrGenerateEventId(JsonNode raw) {
        for (String field : List.of("eventId", "outboxId",
                "transactionId", "sagaId", "verificationId")) {
            if (!raw.path(field).isMissingNode()) {
                return raw.path(field).asText();
            }
        }
        return java.util.UUID.randomUUID().toString();
    }

    private Instant extractTimestamp(JsonNode raw) {
        for (String field : List.of("occurredAt", "completedAt",
                "createdAt", "timestamp", "startedAt")) {
            if (!raw.path(field).isMissingNode()) {
                try {
                    return Instant.parse(raw.path(field).asText());
                } catch (Exception ignored) {}
            }
        }
        return Instant.now();
    }

    private Map<String, Object> extractPayload(JsonNode raw) {
        Map<String, Object> payload = new LinkedHashMap<>();
        raw.fields().forEachRemaining(entry ->
                payload.put(entry.getKey(),
                        entry.getValue().isValueNode()
                                ? entry.getValue().asText()
                                : entry.getValue().toString()));
        return payload;
    }

    private String detectResourceType(String eventType) {
        if (eventType.contains("TRANSACTION") ||
                eventType.contains("LEDGER"))
            return "TRANSACTION";
        if (eventType.contains("ACCOUNT"))
            return "ACCOUNT";
        if (eventType.contains("USER") ||
                eventType.contains("KYC") ||
                eventType.contains("LOGIN"))
            return "USER";
        if (eventType.contains("CONFIG"))
            return "CONFIG";
        return "SYSTEM";
    }

    private String extractResourceId(JsonNode raw, String eventType) {
        if (eventType.contains("TRANSACTION"))
            return raw.path("transactionId").asText(null);
        if (eventType.contains("ACCOUNT"))
            return raw.path("accountId").asText(null);
        if (eventType.contains("USER") || eventType.contains("KYC"))
            return raw.path("userId").asText(null);
        return null;
    }

    private String detectSourceService(String topic) {
        return switch (topic) {
            case "transactions.completed",
                 "transactions.failed",
                 "transactions.initiated"    -> "nexus-transaction-service";
            case "fraud.result",
                 "fraud.flagged"             -> "nexus-fraud-service";
            case "accounts.created",
                 "account.frozen"            -> "nexus-account-service";
            case "users.registered",
                 "identity.verified",
                 "identity.rejected"         -> "nexus-identity-service";
            case "ledger.posted",
                 "ledger.reversed"           -> "nexus-ledger-service";
            case "saga.commands",
                 "saga.replies",
                 "saga.completed"            -> "nexus-saga-orchestrator";
            case "ai.query.logged"           -> "nexus-ai-assistant-service";
            case "analytics.anomalies"       -> "nexus-analytics-service";
            default -> "unknown";
        };
    }

    private boolean isFinancialEvent(String eventType) {
        return eventType.contains("TRANSACTION") ||
                eventType.contains("LEDGER") ||
                eventType.contains("BALANCE") ||
                eventType.contains("TRANSFER");
    }

    private boolean isSensitiveData(String eventType) {
        return eventType.contains("KYC") ||
                eventType.contains("LOGIN") ||
                eventType.contains("FRAUD");
    }

    private boolean requiresSarReview(String eventType,
                                      JsonNode raw) {
        if (eventType.contains("FRAUD_HIGH")) return true;
        double score = raw.path("payload")
                .path("fraudScore").asDouble(-1);
        return score > 90;
    }

    private boolean isRegulatoryRequired(String eventType) {
        return eventType.contains("TRANSACTION") ||
                eventType.contains("LEDGER") ||
                eventType.contains("KYC") ||
                eventType.contains("FRAUD") ||
                eventType.contains("FROZEN");
    }

    private AuditEvent buildFallbackEvent(JsonNode raw,
                                          String topic,
                                          long offset) {
        return new AuditEvent(
                java.util.UUID.randomUUID().toString(),
                "PARSE_ERROR",
                "SYSTEM",
                "WARNING",
                null, null, null,
                "UNKNOWN", null, null,
                Map.of("rawPayload", raw.toString(),
                        "parseError", "true"),
                detectSourceService(topic),
                null, null,
                topic, offset,
                Instant.now(), Instant.now(),
                false, false, false, false
        );
    }
}