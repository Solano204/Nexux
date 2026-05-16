package com.nexus.risk.domain.model;

import com.nexus.risk.domain.model.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * RiskProfile — full structured output from the Plan-then-Act agent.
 *
 * Section 3 structured output: entity(RiskProfile.class)
 * Temperature 0.0: deterministic — same inputs → same score.
 * This is stored in PostgreSQL and cached in Redis.
 *
 * The full profile stays in PostgreSQL (history + compliance).
 * Compact summary goes to Redis (sub-ms serving to Fraud Service).
 */
public record RiskProfile(
        String profileId,
        String userId,
        Instant computedAt,
        Instant validUntil,
        int version,
        int overallRiskScore,
        RiskTier riskTier,
        double confidenceLevel,
        CreditRiskScore creditRisk,
        BehavioralRiskScore behavioralRisk,
        ComplianceRiskScore complianceRisk,
        VelocityRiskProfile velocityProfile,
        UserBehavioralProfile behavioralProfile,
        String modelVersion,
        String agentPlanSummary,
        List<String> toolsUsed,
        List<String> dataGaps,
        List<String> scoringWarnings,
        String regulatoryClassification
) {}
