package com.nexus.audit.query.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Consumes all platform audit events from Kafka and indexes them into
 * audit_event_embeddings (pgvector) for semantic compliance queries.
 *
 * Uses a separate consumer group from audit-write-native so both services
 * independently consume the same topics without interfering.
 *
 * Best-effort: indexing failures are logged but never block Kafka ack.
 */
@Slf4j
@Component
public class AuditIndexingConsumer {

    private final PgVectorStore auditVectorStore;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("America/Mexico_City"));

    public AuditIndexingConsumer(
            @Qualifier("auditVectorStore") PgVectorStore auditVectorStore,
            ObjectMapper objectMapper) {
        this.auditVectorStore = auditVectorStore;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                "transactions.completed",
                "transactions.failed",
                "transactions.initiated",
                "fraud.flagged",
                "fraud.result",
                "accounts.created",
                "users.registered",
                "identity.verified",
                "identity.rejected",
                "saga.completed",
                "ai.query.logged",
                "analytics.anomalies.detected",
                "ledger.posted",
                "ledger.reversed",
                "account.frozen"
            },
            groupId = "audit-vector-indexer"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            indexAuditEvent(record.topic(), record.value(), record.partition(), record.offset());
        } catch (Exception e) {
            log.warn("Failed to index audit event from topic={}: {}", record.topic(), e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }

    private void indexAuditEvent(String topic, String message,
                                  int partition, long offset) throws Exception {
        JsonNode root = objectMapper.readTree(message);

        String eventId = extractStr(root, "eventId", "id", "sagaId", "transactionId");
        String userId  = extractStr(root, "userId", "user_id", "sourceUserId");
        String eventType = deriveEventType(topic, root);
        String severity  = deriveSeverity(topic, root);
        String timestamp = deriveTimestamp(root);
        String sourceService = extractStr(root, "sourceService", "service", "source");
        String traceId = extractStr(root, "traceId", "trace_id");

        String naturalLanguage = buildNaturalLanguage(topic, eventType, root, userId, timestamp);

        String rawKey = topic + "-" + (eventId != null ? eventId : partition + "-" + offset);
        String docId = UUID.nameUUIDFromBytes(rawKey.getBytes()).toString();

        Document doc = Document.builder()
                .id(docId)
                .text(naturalLanguage)
                .metadata("topic", topic)
                .metadata("event_type", eventType)
                .metadata("user_id", userId != null ? userId : "unknown")
                .metadata("severity", severity)
                .metadata("source_service", sourceService != null ? sourceService : "unknown")
                .metadata("trace_id", traceId != null ? traceId : "")
                .metadata("timestamp", timestamp)
                .build();

        auditVectorStore.add(List.of(doc));
        log.debug("Indexed audit event: topic={} eventType={} userId={}", topic, eventType, userId);
    }

    private String buildNaturalLanguage(String topic, String eventType,
                                        JsonNode root, String userId, String timestamp) {
        String amount = extractStr(root, "amount", "debitedAmount", "reservedAmount",
                                   "creditedAmount", "releasedAmount");
        String currency = extractStr(root, "currency");
        String status = extractStr(root, "status", "outcome", "decision");
        String accountId = extractStr(root, "accountId", "sourceAccountId");
        String description = extractStr(root, "description", "reason", "failureReason");
        String riskScore = extractStr(root, "riskScore", "score");

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timestamp).append("] ");
        sb.append(eventType);

        if (userId != null && !userId.isBlank()) {
            sb.append(" — User ").append(shortId(userId));
        }
        if (accountId != null && !accountId.isBlank()) {
            sb.append(", account ").append(shortId(accountId));
        }
        if (amount != null && !amount.isBlank()) {
            String cur = currency != null ? currency : "MXN";
            sb.append(", amount ").append(cur).append(" ").append(amount);
        }
        if (status != null && !status.isBlank()) {
            sb.append(", status: ").append(status);
        }
        if (riskScore != null && !riskScore.isBlank()) {
            sb.append(", risk score: ").append(riskScore);
        }
        if (description != null && !description.isBlank()) {
            sb.append(". ").append(description);
        }

        sb.append(". Topic: ").append(topic);
        return sb.toString();
    }

    private String deriveEventType(String topic, JsonNode root) {
        String explicit = extractStr(root, "eventType", "type", "replyType", "commandType");
        if (explicit != null && !explicit.isBlank()) return explicit;
        return topic.toUpperCase().replace(".", "_").replace("-", "_");
    }

    private String deriveSeverity(String topic, JsonNode root) {
        String explicit = extractStr(root, "severity");
        if (explicit != null) return explicit;
        if (topic.contains("fraud") || topic.contains("frozen")) return "WARNING";
        if (topic.contains("failed") || topic.contains("rejected")) return "ERROR";
        if (topic.contains("anomal")) return "WARNING";
        return "INFO";
    }

    private String deriveTimestamp(JsonNode root) {
        String ts = extractStr(root, "occurredAt", "timestamp", "createdAt",
                               "eventTimestamp", "repliedAt");
        if (ts != null) {
            try {
                return FORMATTER.format(Instant.parse(ts));
            } catch (Exception ignored) {
                return ts;
            }
        }
        return FORMATTER.format(Instant.now());
    }

    private String extractStr(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root.path(key);
            if (!node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
            // also try nested payload
            JsonNode payload = root.path("payload");
            if (!payload.isMissingNode()) {
                JsonNode nested = payload.path(key);
                if (!nested.isMissingNode() && !nested.isNull() && !nested.asText().isBlank()) {
                    return nested.asText();
                }
            }
        }
        return null;
    }

    private String shortId(String id) {
        if (id == null) return "unknown";
        return id.length() > 8 ? id.substring(0, 8) + "..." : id;
    }
}
