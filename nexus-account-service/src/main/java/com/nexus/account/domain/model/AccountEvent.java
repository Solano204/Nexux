package com.nexus.account.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AccountEvent — Immutable balance change audit trail.
 *
 * Records EVERY balance operation with before/after snapshots.
 * Backed by PostgreSQL immutability trigger (V3 migration)
 * that prevents UPDATE/DELETE on account_events rows.
 *
 * No FK to accounts — must retain events after account closure
 * (regulatory/compliance requirement).
 */
@Entity
@Table(name = "account_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "eventId")
@ToString
public class AccountEvent {

    @Id
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "amount", precision = 20, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "available_before", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal availableBefore;

    @Column(name = "available_after", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal availableAfter;

    @Column(name = "reserved_before", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal reservedBefore;

    @Column(name = "reserved_after", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal reservedAfter;

    /**
     * JSONB details column — stores additional context like
     * source/target accounts, categories, descriptions.
     * Uses Hypersistence JsonType for proper PostgreSQL JSONB mapping.
     */
    @Column(name = "details", columnDefinition = "jsonb", updatable = false)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private java.util.Map<String, Object> details;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    @PrePersist
    void prePersist() {
        if (eventId == null) eventId = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
        if (reservedBefore == null) reservedBefore = BigDecimal.ZERO;
        if (reservedAfter == null) reservedAfter = BigDecimal.ZERO;
    }
}