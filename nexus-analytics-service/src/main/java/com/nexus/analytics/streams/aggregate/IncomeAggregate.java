package com.nexus.analytics.streams.aggregate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * IncomeAggregate — Kafka Streams state store value.
 *
 * Tracks incoming money (credits) per user within a 30-day window
 * (Topology D). Used for savings rate calculation:
 * savingsRate = (income - spending) / income.
 */
@Getter
public class IncomeAggregate {

    private final BigDecimal totalAmount;
    private final int transactionCount;
    private final String currency;
    private final Map<String, BigDecimal> byTransactionType;
    private final Instant lastUpdated;

    @JsonCreator
    public IncomeAggregate(
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("transactionCount") int transactionCount,
            @JsonProperty("currency") String currency,
            @JsonProperty("byTransactionType") Map<String, BigDecimal> byTransactionType,
            @JsonProperty("lastUpdated") Instant lastUpdated) {
        this.totalAmount = totalAmount;
        this.transactionCount = transactionCount;
        this.currency = currency;
        this.byTransactionType = byTransactionType != null
                ? byTransactionType : new HashMap<>();
        this.lastUpdated = lastUpdated;
    }

    public static IncomeAggregate zero() {
        return new IncomeAggregate(
                BigDecimal.ZERO, 0, "MXN", new HashMap<>(), null);
    }

    public IncomeAggregate add(BigDecimal amount, String currency,
                               String transactionType) {
        Map<String, BigDecimal> updated = new HashMap<>(byTransactionType);
        updated.merge(transactionType, amount, BigDecimal::add);

        return new IncomeAggregate(
                totalAmount.add(amount),
                transactionCount + 1,
                currency != null ? currency : this.currency,
                updated,
                Instant.now()
        );
    }
}