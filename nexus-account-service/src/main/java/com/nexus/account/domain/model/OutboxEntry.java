package com.nexus.account.domain.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * OutboxEntry — Outbox Pattern for Debezium CDC.
 *
 * Written atomically with domain changes in the same PostgreSQL transaction.
 * Debezium reads the WAL (Write-Ahead Log) and publishes to Kafka.
 *
 * This guarantees at-least-once delivery of domain events to Kafka
 * without distributed transactions (2PC). The combination of:
 * 1. Atomic write (domain change + outbox entry in same TX)
 * 2. Debezium CDC reading PostgreSQL WAL
 * 3. Kafka consumer idempotency checks
 * ...provides exactly-once semantics end-to-end.
 *
 * The processedAt field is set by Debezium connector after publishing.
 * Cleanup job deletes entries older than 7 days where processedAt is set.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "outboxId")
@ToString
public class OutboxEntry {

    @Id
    @Column(name = "outbox_id", updatable = false)
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false, length = 100, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    /**
     * JSONB payload — the serialized domain event.
     * Contains all data needed for downstream consumers to
     * process the event without querying this service.
     */
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private Object payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (outboxId == null) outboxId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * Factory method for creating outbox entries.
     * Used by AccountCommandService after every state change.
     */
    public static OutboxEntry of(String aggregateType, UUID aggregateId,
                                 String eventType, Object payload) {
        return OutboxEntry.builder()
                .outboxId(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .createdAt(Instant.now())
                .build();
    }
}