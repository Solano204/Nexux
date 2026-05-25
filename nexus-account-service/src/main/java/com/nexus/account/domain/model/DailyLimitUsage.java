package com.nexus.account.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * DailyLimitUsage — Separate table for efficient batch resets.
 *
 * One row per account per day. Midnight job deletes previous days —
 * more efficient than updating millions of account rows.
 *
 * Composite primary key: (account_id, usage_date)
 *
 * This table complements the daily_transaction_used field on Account.
 * The Account field is the authoritative source during live transactions;
 * this table provides historical daily usage data for analytics and
 * compliance reporting.
 */
@Entity
@Table(name = "daily_limit_usage")
@IdClass(DailyLimitUsage.DailyLimitUsageId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DailyLimitUsage {

    @Id
    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Id
    @Column(name = "usage_date", updatable = false)
    private LocalDate usageDate;

    @Column(name = "amount_used", nullable = false, precision = 20, scale = 4)
    private BigDecimal amountUsed;

    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @PrePersist
    void prePersist() {
        if (usageDate == null) usageDate = LocalDate.now();
        if (amountUsed == null) amountUsed = BigDecimal.ZERO;
        if (transactionCount == null) transactionCount = 0;
        if (lastUpdatedAt == null) lastUpdatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        lastUpdatedAt = Instant.now();
    }

    /**
     * Adds a transaction amount to today's usage.
     */
    public void recordTransaction(BigDecimal amount) {
        this.amountUsed = this.amountUsed.add(amount);
        this.transactionCount = this.transactionCount + 1;
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * Reverses a transaction amount (SAGA compensation).
     */
    public void reverseTransaction(BigDecimal amount) {
        this.amountUsed = this.amountUsed.subtract(amount).max(BigDecimal.ZERO);
        this.transactionCount = Math.max(0, this.transactionCount - 1);
        this.lastUpdatedAt = Instant.now();
    }

    /**
     * Composite primary key for (account_id, usage_date).
     */
    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyLimitUsageId implements Serializable {
        private UUID accountId;
        private LocalDate usageDate;
    }
}