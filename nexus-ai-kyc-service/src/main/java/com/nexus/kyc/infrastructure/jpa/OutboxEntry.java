package com.nexus.kyc.infrastructure.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * OutboxEntry — PostgreSQL kyc_outbox table (V1__create_kyc_audit.sql).
 *
 * Same Outbox+Debezium pattern as the other 6 services on the platform
 * (see CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3): the row
 * is written in the same @Transactional as kyc_audit_entries, Debezium reads
 * the WAL directly and publishes to Kafka - it never writes back to this
 * table. aggregateType doubles as the Kafka topic name at write time
 * (EventRouter routes by aggregate_type), matching the convention used by
 * identity/account/transaction/ledger/fraud's outbox tables.
 *
 * This table existed in the schema since the service's first migration but
 * had no Java entity/repository until this change - see Section 6 of the
 * doc above for why (it replaces two unreliable synchronous HTTP callbacks
 * to identity-service with a reliable outbox-backed Kafka publish).
 */
@Entity
@Table(name = "kyc_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEntry {

    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
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

    @PrePersist
    void prePersist() {
        if (outboxId == null) outboxId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

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
