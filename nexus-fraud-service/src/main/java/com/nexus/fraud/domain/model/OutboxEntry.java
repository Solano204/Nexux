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
}