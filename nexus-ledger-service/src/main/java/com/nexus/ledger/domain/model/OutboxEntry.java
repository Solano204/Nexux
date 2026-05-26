package com.nexus.ledger.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox Pattern entry for Debezium CDC.
 * Written atomically with ledger entries in the same transaction.
 * Debezium reads → publishes to Kafka topics.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "outboxId")
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
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

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
}