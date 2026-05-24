package com.nexus.account.domain.model;

import com.nexus.account.domain.model.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * BalanceReservation — Audit trail for in-flight SAGA operations.
 *
 * Unique constraint on (account_id, transaction_id) ensures
 * idempotent reservation handling for Kafka at-least-once delivery.
 *
 * Lifecycle: ACTIVE → FINALIZED (success) or RELEASED (compensation)
 *            ACTIVE → RELEASED_BY_EXPIRY (TTL exceeded, 24h safety net)
 *
 * One reservation per transaction per account — the uq_active_reservation
 * constraint in V2 migration enforces this at the database level.
 */
@Entity
@Table(name = "balance_reservations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_active_reservation",
                columnNames = {"account_id", "transaction_id"}
        ))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "reservationId")
@ToString
public class BalanceReservation {

    @Id
    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "reserved_amount", nullable = false, precision = 20, scale = 4, updatable = false)
    private BigDecimal reservedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @PrePersist
    void prePersist() {
        if (reservationId == null) reservationId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = ReservationStatus.ACTIVE;
        if (expiresAt == null) expiresAt = Instant.now().plus(java.time.Duration.ofHours(24));
    }

    /**
     * Marks this reservation as finalized (transfer completed successfully).
     */
    public void finalize_() {
        this.status = ReservationStatus.FINALIZED;
        this.finalizedAt = Instant.now();
    }

    /**
     * Marks this reservation as released (SAGA compensation).
     */
    public void release() {
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = Instant.now();
    }

    /**
     * Marks this reservation as released by expiry (safety net).
     */
    public void releaseByExpiry() {
        this.status = ReservationStatus.RELEASED_BY_EXPIRY;
        this.releasedAt = Instant.now();
    }

    public boolean isActive() {
        return this.status == ReservationStatus.ACTIVE;
    }

    public boolean isExpired() {
        return isActive() && Instant.now().isAfter(expiresAt);
    }
}