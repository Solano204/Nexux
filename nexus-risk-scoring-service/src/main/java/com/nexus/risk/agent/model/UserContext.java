package com.nexus.risk.agent.model;


/**
 * UserContext — loaded before planning.
 * Tells the planner what it needs to know to choose tools.
 */
public record UserContext(
        String userId,
        int accountAgeMonths,
        List<String> accountTypes,
        boolean hasTransactionHistory,
        int monthsOfHistoryAvailable,
        String previousRiskTier,         // null if first computation
        String kycStatus,
        boolean recentFraudFlags,
        boolean significantBehavioralChange
) {}