package com.nexus.analytics.domain.model;

import com.nexus.analytics.domain.model.enums.AnomalyType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class SpendingAnomaly {

    private String anomalyId;
    private String userId;
    private AnomalyType type;
    private String category;
    private String severity;          // HIGH, MEDIUM, LOW
    private double zScore;
    private double percentageChange;
    private BigDecimal absoluteChange;
    private BigDecimal historicalMean;
    private BigDecimal currentValue;
    private String currency;
    private List<Map<String, Object>> topContributingMerchants;
    private Instant detectedAt;
    private boolean reportedToUser;
}