package com.nexus.audit.write.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * AuditEvent — canonical structure for all audit events.
 *
 * Every event from every service is normalized into this record
 * before being written to Elasticsearch.
 *
 * @RegisterForReflection is CRITICAL for Quarkus native —
 * without it, GraalVM removes the class during native compilation
 * and Jackson serialization fails at runtime.
 */
@RegisterForReflection
public record AuditEvent(

        @JsonProperty("eventId")
        String eventId,                    // UUID v7 — stable across Kafka redeliveries

        @JsonProperty("eventType")
        String eventType,                  // TRANSACTION_COMPLETED, FRAUD_FLAGGED, etc.

        @JsonProperty("category")
        String category,                   // FINANCIAL, SECURITY, COMPLIANCE, SYSTEM, AI

        @JsonProperty("severity")
        String severity,                   // INFO, WARNING, HIGH, CRITICAL

        // ── Actor ────────────────────────────────────────────────
        @JsonProperty("userId")
        String userId,

        @JsonProperty("sessionId")
        String sessionId,

        @JsonProperty("ipAddress")
        String ipAddress,

        // ── Resource ─────────────────────────────────────────────
        @JsonProperty("resourceType")
        String resourceType,               // ACCOUNT, TRANSACTION, USER, CONFIG

        @JsonProperty("resourceId")
        String resourceId,

        @JsonProperty("resourceOwnerId")
        String resourceOwnerId,

        // ── Event-specific payload ────────────────────────────────
        @JsonProperty("payload")
        Map<String, Object> payload,

        // ── Technical provenance ──────────────────────────────────
        @JsonProperty("sourceService")
        String sourceService,

        @JsonProperty("traceId")
        String traceId,

        @JsonProperty("spanId")
        String spanId,

        @JsonProperty("kafkaTopic")
        String kafkaTopic,

        @JsonProperty("kafkaOffset")
        long kafkaOffset,

        // ── Timing ────────────────────────────────────────────────
        @JsonProperty("eventTimestamp")
        Instant eventTimestamp,            // when the business event occurred

        @JsonProperty("ingestedAt")
        Instant ingestedAt,                // when audit service received it

        // ── Compliance flags ──────────────────────────────────────
        @JsonProperty("isFinancialEvent")
        boolean isFinancialEvent,

        @JsonProperty("isSensitiveData")
        boolean isSensitiveData,

        @JsonProperty("requiresSarReview")
        boolean requiresSarReview,

        @JsonProperty("isRegulatoryRequired")
        boolean isRegulatoryRequired
) {}