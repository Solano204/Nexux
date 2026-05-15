package com.nexus.fraud.domain.model;

import com.nexus.fraud.domain.model.enums.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FraudDecision — Core structured output record from the ReAct agent.
 *
 * This is both:
 * 1. The Spring AI entity() return type (structured output, Section 3)
 * 2. The SAGA reply payload (FraudClearedReply / FraudRejectedReply)
 * 3. The compliance audit record (stored in fraud_decisions PostgreSQL)
 *
 * Score interpretation:
 *   0-29:   APPROVE (consistent with user history, no significant anomalies)
 *   30-69:  REVIEW  (moderate risk — compliance officer reviews)
 *   70-100: REJECT  (clear fraud indicators — transaction blocked)
 *
 * Pattern: Structured Output (Section 3)
 * Pattern: Value Object — immutable after AI generates it
 */
public record FraudDecision(
        String transactionId,

        /** Final outcome: APPROVE, REVIEW, or REJECT */
        FraudDecisionOutcome decision,

        /**
         * Composite risk score 0.00-100.00.
         * Computed by the LLM weighting triggering factors.
         */
        BigDecimal riskScore,

        /**
         * AI confidence in this decision 0.000-1.000.
         * Low confidence (< 0.7) suggests policy retrieval was poor
         * or signals were contradictory.
         */
        BigDecimal confidenceLevel,

        /** Factors that INCREASED the risk score */
        List<TriggeringFactor> triggeringFactors,

        /** Factors that ARGUED AGAINST rejection (for APPROVE) */
        List<String> clearingFactors,

        /**
         * Full natural language reasoning.
         * Used by compliance officers and by the AI Assistant
         * when explaining rejections to users.
         */
        String reasoning,

        /** Specific policy documents that guided this decision */
        List<PolicyCitation> policyCitations,

        /** Summary of what each tool returned */
        List<ToolCallSummary> toolCallSummary,

        /** What should happen next */
        RecommendedAction recommendedAction,

        /** For REVIEW decisions: how urgently needs review */
        String reviewPriority,

        /** All raw data collected — for full audit trail */
        Map<String, Object> rawSignals,

        Instant analysisStartedAt,
        Instant analysisCompletedAt,
        Long analysisTimeMs
) {

    /**
     * Convenience: is the transaction approved to proceed?
     */
    public boolean isApproved() {
        return decision == FraudDecisionOutcome.APPROVE;
    }

    /**
     * Convenience: should the transaction be blocked?
     */
    public boolean isRejected() {
        return decision == FraudDecisionOutcome.REJECT;
    }

    /**
     * Convenience: needs human review?
     */
    public boolean needsReview() {
        return decision == FraudDecisionOutcome.REVIEW;
    }

    /**
     * Is this a high-severity case (score >= 85)?
     * Triggers immediate compliance alert and SAR consideration.
     */
    public boolean isHighSeverity() {
        return riskScore != null &&
                riskScore.compareTo(new BigDecimal("85")) >= 0;
    }

    // ─── Inner records ────────────────────────────────────

    /**
     * A specific factor that contributed to the risk score.
     * Maps directly to what regulators require in adverse action notices.
     */
    public record TriggeringFactor(
            /** VELOCITY, GEOLOCATION, MERCHANT_RISK, BEHAVIORAL_ANOMALY,
             AMOUNT_ANOMALY, NEW_COUNTERPARTY, POLICY_VIOLATION */
            String category,
            String description,
            BigDecimal weight,      // contribution to risk score (0-1)
            String evidence,        // the specific data point
            String policyReference  // which policy if applicable
    ) {}

    /** A regulatory/internal policy that was applied */
    public record PolicyCitation(
            String policyId,
            String policyTitle,
            String relevantSection,
            String applicationExplanation
    ) {}

    /** Summary of a single tool call for the audit record */
    public record ToolCallSummary(
            String toolName,
            String resultSummary,
            long durationMs,
            boolean succeeded
    ) {}
}