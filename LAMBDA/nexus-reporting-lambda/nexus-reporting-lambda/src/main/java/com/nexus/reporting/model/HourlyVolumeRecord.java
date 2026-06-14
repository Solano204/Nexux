package com.nexus.reporting.model;

import java.math.BigDecimal;

public record HourlyVolumeRecord(
    int hour,
    BigDecimal totalVolume,
    int transactionCount
) {
    public static HourlyVolumeRecord zero(int hour) {
        return new HourlyVolumeRecord(hour, BigDecimal.ZERO, 0);
    }
}
