package com.nexus.risk.domain.model;

import com.nexus.risk.domain.model.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;


public record VelocityRiskProfile(
        double meanTransactionAmount,
        double stdDevTransactionAmount,
        Map<Integer, Double> activeHours,
        Map<String, Double> transactionTypeFrequency,
        List<String> frequentCounterparties,
        List<String> knownDeviceFingerprints,
        String typicalActivityHours,
        double offHourRiskScore
) {}

