package com.nexus.risk.agent.model;

import lombok.Builder;
import lombok.With;

import java.util.List;

/**
 * Lightweight snapshot of a user's account metadata used during the
 * PLAN phase.  Heavy data (transactions, KYC documents, etc.) is fetched
 * by individual tools during the EXECUTE phase.
 *
 * FIX 2 explanation
 * ─────────────────
 * The original code called UserContext.builder() but UserContext was defined
 * as a plain Java record without a Lombok @Builder annotation.  Plain records
 * expose only their canonical constructor — there is no builder() factory
 * method unless you add @Builder (Lombok) or write one manually.
 *
 * Two options — pick ONE:
 *
 * OPTION A (this file): Lombok @Builder on a regular class.
 *   Pros: concise, familiar; works with any Java version.
 *   Cons: requires Lombok on the classpath (already used elsewhere in the project).
 *
 * OPTION B: Keep it a record and drop the builder pattern entirely.
 *   Replace UserContext.builder()...build() in RiskScoringAgent with:
 *
 *     new UserContext(
 *         userId,
 *         getAccountAgeMonths(userId),
 *         getAccountTypes(userId),
 *         hasTransactionHistory(userId),
 *         getMonthsOfHistory(userId),
 *         existing.map(RiskProfileJpaEntity::getRiskTier).orElse(null),
 *         getKycStatus(userId),
 *         hasRecentFraudFlags(userId),
 *         hasSignificantBehavioralChange(userId)
 *     );
 *
 * This file implements OPTION A.
 */
@Builder
@With                    // generates withXxx() copy-methods — handy for tests
public class UserContext {

    /** Internal user identifier. */
    private final String userId;

    /** How long the account has existed, in full calendar months. */
    private final int accountAgeMonths;

    /** E.g. ["CHECKING", "SAVINGS", "INVESTMENT"]. */
    private final List<String> accountTypes;

    /** True if at least one transaction exists in any account. */
    private final boolean hasTransactionHistory;

    /** Number of months for which transaction history is available. */
    private final int monthsOfHistoryAvailable;

    /**
     * The risk tier recorded on the most recent persisted RiskProfile,
     * or {@code null} if no prior profile exists.
     */
    private final String previousRiskTier;

    /** KYC status string, e.g. "VERIFIED", "PENDING", "FAILED". */
    private final String kycStatus;

    /** True if a fraud flag was raised in the last 90 days. */
    private final boolean recentFraudFlags;

    /**
     * True if the behavioural-change detector found an anomaly
     * compared with the previous scoring window.
     */
    private final boolean significantBehavioralChange;

    // ── Accessors (no Lombok @Getter needed — added explicitly so the
    //    record-style accessor calls in RiskScoringAgent compile as-is) ────

    public String       userId()                      { return userId; }
    public int          accountAgeMonths()            { return accountAgeMonths; }
    public List<String> accountTypes()               { return accountTypes; }
    public boolean      hasTransactionHistory()      { return hasTransactionHistory; }
    public int          monthsOfHistoryAvailable()   { return monthsOfHistoryAvailable; }
    public String       previousRiskTier()           { return previousRiskTier; }
    public String       kycStatus()                  { return kycStatus; }
    public boolean      recentFraudFlags()           { return recentFraudFlags; }
    public boolean      significantBehavioralChange(){ return significantBehavioralChange; }
}