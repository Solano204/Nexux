package com.nexus.saga.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEntry {

    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Type(JsonType.class)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (outboxId == null)      outboxId      = UUID.randomUUID();
        if (createdAt == null)     createdAt     = Instant.now();
        if (aggregateType == null) aggregateType = "SAGA";
    }

    /**
     * Domain method — keeps mutation explicit and avoids
     * exposing a raw setter on an otherwise immutable entity.
     */
    public void markProcessed() {
        this.processedAt = Instant.now();
    }

    public static OutboxEntry forSagaCommand(
            String topic,
            String sagaId,
            Object command,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            return OutboxEntry.builder()
                    .topic(topic)
                    .aggregateType("SAGA")
                    .aggregateId(UUID.fromString(sagaId))
                    .eventType(command.getClass().getSimpleName())
                    .payload(mapper.valueToTree(command))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build outbox entry for saga command", e);
        }
    }

    public static OutboxEntry forDomainEvent(
            String topic,
            String aggregateId,
            Object event,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            return OutboxEntry.builder()
                    .topic(topic)
                    .aggregateType("SAGA_EVENT")
                    .aggregateId(UUID.fromString(aggregateId))
                    .eventType(event.getClass().getSimpleName())
                    .payload(mapper.valueToTree(event))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build outbox entry for domain event", e);
        }
    }
}