package com.nexus.identity.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox Entry — Written in same transaction as domain changes.
 * Debezium reads via PostgreSQL WAL → publishes to Kafka.
 *
 * This is the core of the Outbox Pattern guaranteeing
 * exactly-once event publication even under partial failures.
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
    @Column(name = "outbox_id", updatable = false)
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Type(JsonBinaryType.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "created_at", updatable = false)
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

    @PrePersist
    void prePersist() {
        if (outboxId == null) outboxId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public static OutboxEntry of(String aggregateType, UUID aggregateId,
                                 String eventType, JsonNode payload) {
        return OutboxEntry.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
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