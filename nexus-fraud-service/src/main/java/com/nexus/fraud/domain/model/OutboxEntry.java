package com.nexus.fraud.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * OutboxEntry — Transactional outbox for Debezium CDC.
 *
 * Fraud-specific events:
 * - FraudHighSeverityAlert (riskScore >= 85)
 * - FraudReviewQueued (decision = REVIEW)
 * - MerchantFraudPatternDetected
 *
 * Debezium reads this table and publishes to Kafka.
 * Row is written in the same transaction as the fraud decision.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEntry {

    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Type(JsonBinaryType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "trace_id", updatable = false)
    private String traceId;

    @Column(name = "span_id", updatable = false)
    private String spanId;

    @Column(name = "trace_sampled", updatable = false)
    private String traceSampled;

    @Column(name = "trace_b3", updatable = false)
    private String traceB3;

    /**
     * Static factory — consistent with account-service OutboxEntry.of() pattern.
     */
    public static OutboxEntry of(String aggregateType, UUID aggregateId,
                                 String eventType, JsonNode payload) {
        return OutboxEntry.builder()
                .outboxId(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Stamps the current Micrometer span onto this entry, in single-header
     * B3 format ("traceId-spanId-sampled") for trace_b3 since Kafka Connect
     * SMTs can only copy a field 1:1 into a header, not concatenate one.
     * No-op if there's no active span (nullable columns absorb it).
     */
    public void attachTraceContext(io.micrometer.tracing.Tracer tracer) {
        io.micrometer.tracing.Span span = tracer.currentSpan();
        if (span == null) return;
        var ctx = span.context();
        this.traceId = ctx.traceId();
        this.spanId = ctx.spanId();
        this.traceSampled = Boolean.TRUE.equals(ctx.sampled()) ? "1" : "0";
        this.traceB3 = ctx.traceId() + "-" + ctx.spanId() + "-" + this.traceSampled;
    }
}