package com.nexus.analytics.streams.aggregate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CategorySpendingAggregate — Kafka Streams state store value.
 *
 * Accumulates per-category spending within a time window.
 * Designed for JSON serialization (Kafka Streams state must serialize).
 * Keeps top 10 merchants in memory to control state store size.
 *
 * Immutable update pattern: each call to add() returns a new aggregate.
 * This is required for Kafka Streams' functional DSL.
 */
@Getter
public class CategorySpendingAggregate {

    private final BigDecimal totalAmount;
    private final int transactionCount;
    private final String currency;
    private final Map<String, BigDecimal> merchantBreakdown;
    private final BigDecimal averageTransactionAmount;
    private final BigDecimal maxTransactionAmount;
    private final Instant firstTransactionAt;
    private final Instant lastUpdatedAt;

    @JsonCreator
    public CategorySpendingAggregate(
            @JsonProperty("totalAmount") BigDecimal totalAmount,
            @JsonProperty("transactionCount") int transactionCount,
            @JsonProperty("currency") String currency,
            @JsonProperty("merchantBreakdown")
            Map<String, BigDecimal> merchantBreakdown,
            @JsonProperty("averageTransactionAmount")
            BigDecimal averageTransactionAmount,
            @JsonProperty("maxTransactionAmount")
            BigDecimal maxTransactionAmount,
            @JsonProperty("firstTransactionAt") Instant firstTransactionAt,
            @JsonProperty("lastUpdatedAt") Instant lastUpdatedAt) {
        this.totalAmount = totalAmount;
        this.transactionCount = transactionCount;
        this.currency = currency;
        this.merchantBreakdown = merchantBreakdown != null
                ? merchantBreakdown : new LinkedHashMap<>();
        this.averageTransactionAmount = averageTransactionAmount;
        this.maxTransactionAmount = maxTransactionAmount;
        this.firstTransactionAt = firstTransactionAt;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    /**
     * Zero value — used as Kafka Streams initializer.
     * Required by .aggregate(CategorySpendingAggregate::zero, ...)
     */
    public static CategorySpendingAggregate zero() {
        return new CategorySpendingAggregate(
                BigDecimal.ZERO, 0, "MXN",
                new LinkedHashMap<>(),
                BigDecimal.ZERO, BigDecimal.ZERO,
                null, null
        );
    }

    /**
     * Immutable add — returns new aggregate with this transaction applied.
     * Kafka Streams requires aggregators to return new instances.
     */
    public CategorySpendingAggregate add(BigDecimal amount,
                                         String currency,
                                         String merchant) {

        Map<String, BigDecimal> updatedMerchants =
                new LinkedHashMap<>(merchantBreakdown);

        if (merchant != null && !merchant.isBlank()) {
            updatedMerchants.merge(merchant, amount, BigDecimal::add);
        }

        // Keep only top 10 merchants to control state store size
        Map<String, BigDecimal> topMerchants = updatedMerchants
                .entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue()
                        .reversed())
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new));

        BigDecimal newTotal = totalAmount.add(amount);
        int newCount = transactionCount + 1;
        BigDecimal newMax = amount.compareTo(maxTransactionAmount) > 0
                ? amount : maxTransactionAmount;
        BigDecimal newAvg = newTotal.divide(
                BigDecimal.valueOf(newCount), 2, RoundingMode.HALF_UP);

        return new CategorySpendingAggregate(
                newTotal, newCount,
                currency != null ? currency : this.currency,
                topMerchants, newAvg, newMax,
                firstTransactionAt != null
                        ? firstTransactionAt : Instant.now(),
                Instant.now()
        );
    }

    @JsonIgnore
    public List<Map.Entry<String, BigDecimal>> getTopMerchantsSorted() {
        return merchantBreakdown.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue()
                        .reversed())
                .toList();
    }
}