package com.nexus.risk.domain.model;

import com.nexus.risk.domain.model.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record ComplianceRiskScore(
        int amlRiskScore,
        boolean isPep,
        boolean hasSanctionsExposure,
        List<String> highRiskCountries,
        boolean hasStructuringSignals,
        boolean hasLayeringSignals,
        String riskTier              // LOW, MEDIUM, HIGH, CRITICAL
) {}
