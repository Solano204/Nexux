package com.nexus.analytics.streams.aggregate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * VolumeAggregate — Platform-level transaction volume per time window.
 * Used by the platform-volume-hourly-store topology.
 */
@Getter
public class VolumeAggregate {

    private final long transactionCount;
    private final BigDecimal totalVolume;
    private final String currency;
    private final Map<String, Long> byTransactionType;
    private final Instant windowStart;
    private final Instant lastUpdated;

    @JsonCreator
    public VolumeAggregate(
            @JsonProperty("transactionCount") long transactionCount,
            @JsonProperty("totalVolume") BigDecimal totalVolume,
            @JsonProperty("currency") String currency,
            @JsonProperty("byTransactionType")
            Map<String, Long> byTransactionType,
            @JsonProperty("windowStart") Instant windowStart,
            @JsonProperty("lastUpdated") Instant lastUpdated) {
        this.transactionCount = transactionCount;
        this.totalVolume = totalVolume;
        this.currency = currency;
        this.byTransactionType = byTransactionType != null
                ? byTransactionType : new HashMap<>();
        this.windowStart = windowStart;
        this.lastUpdated = lastUpdated;
    }

    public static VolumeAggregate zero() {
        return new VolumeAggregate(
                0L, BigDecimal.ZERO, "MXN",
                new HashMap<>(), Instant.now(), null);
    }

    public VolumeAggregate add(BigDecimal amount, String currency,
                               String transactionType) {
        Map<String, Long> updatedTypes =
                new HashMap<>(byTransactionType);
        updatedTypes.merge(
                transactionType != null ? transactionType : "UNKNOWN",
                1L, Long::sum);

        return new VolumeAggregate(
                transactionCount + 1,
                totalVolume.add(amount),
                currency != null ? currency : this.currency,
                updatedTypes,
                windowStart,
                Instant.now()
        );
    }
}