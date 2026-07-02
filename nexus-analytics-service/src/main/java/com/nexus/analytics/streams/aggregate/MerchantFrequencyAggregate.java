package com.nexus.analytics.streams.aggregate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * MerchantFrequencyAggregate — Kafka Streams state store value.
 *
 * Tracks visit count + total spending at a specific merchant
 * within a 30-day sliding window (Topology C).
 * Used for "Top merchants this month" leaderboard.
 */
@Getter
public class MerchantFrequencyAggregate {

    private final String merchantName;
    private final long visitCount;
    private final BigDecimal totalSpent;
    private final String currency;
    private final Instant firstVisit;
    private final Instant lastVisit;

    @JsonCreator
    public MerchantFrequencyAggregate(
            @JsonProperty("merchantName") String merchantName,
            @JsonProperty("visitCount") long visitCount,
            @JsonProperty("totalSpent") BigDecimal totalSpent,
            @JsonProperty("currency") String currency,
            @JsonProperty("firstVisit") Instant firstVisit,
            @JsonProperty("lastVisit") Instant lastVisit) {
        this.merchantName = merchantName;
        this.visitCount = visitCount;
        this.totalSpent = totalSpent;
        this.currency = currency;
        this.firstVisit = firstVisit;
        this.lastVisit = lastVisit;
    }

    public static MerchantFrequencyAggregate zero() {
        return new MerchantFrequencyAggregate(
                null, 0L, BigDecimal.ZERO, "MXN", null, null);
    }

    public MerchantFrequencyAggregate add(String merchant,
                                          BigDecimal amount,
                                          String currency) {
        return new MerchantFrequencyAggregate(
                merchant,
                visitCount + 1,
                totalSpent.add(amount),
                currency != null ? currency : this.currency,
                firstVisit != null ? firstVisit : Instant.now(),
                Instant.now()
        );
    }
}