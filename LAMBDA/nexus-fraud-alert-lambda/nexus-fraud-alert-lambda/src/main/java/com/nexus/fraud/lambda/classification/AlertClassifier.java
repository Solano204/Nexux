package com.nexus.fraud.lambda.classification;

import com.nexus.fraud.lambda.model.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Alert Classifier — derives severity and category from fraud decision.
 *
 * Category detection uses compound pattern matching:
 * - ACCOUNT_TAKEOVER_SUSPECTED: geo anomaly + behavioral + velocity
 * - CARD_TESTING_SUSPECTED: velocity + merchant risk + small amount
 * - AML_PATTERN_DETECTED: AML policy citations in fraud decision
 * - UNUSUAL_LOCATION: geo only, no velocity
 * - VELOCITY_ANOMALY: velocity only
 * - HIGH_RISK_TRANSACTION: fallback
 *
 * requiresAccountAction drives urgency:
 * - BLOCK_ACCOUNT_TEMPORARY/PERMANENT → true
 * - ESCALATE_TO_COMPLIANCE → true
 * - MONITOR → false
 */
public class AlertClassifier {

    private static final BigDecimal SMALL_AMOUNT_THRESHOLD =
            BigDecimal.valueOf(500);

    private static final Set<String> ACCOUNT_ACTION_OUTCOMES = Set.of(
            "BLOCK_ACCOUNT_TEMPORARY",
            "BLOCK_ACCOUNT_PERMANENT",
            "ESCALATE_TO_COMPLIANCE",
            "FILE_SAR"
    );

    public AlertClassification classify(FraudAlertEvent alert) {
        List<String> factorCategories = alert.triggeringFactors() != null
                ? alert.triggeringFactors().stream()
                        .map(TriggeringFactor::category)
                        .toList()
                : List.of();

        String alertCategory = deriveCategory(
                factorCategories, alert);

        AlertSeverity severity =
                AlertSeverity.fromScore(alert.riskScore());

        boolean requiresAccountAction = alert.recommendedAction() != null
                && ACCOUNT_ACTION_OUTCOMES.contains(
                alert.recommendedAction().toUpperCase());

        return new AlertClassification(
                severity,
                alertCategory,
                requiresAccountAction,
                alert.recommendedAction() != null
                        ? alert.recommendedAction() : "MONITOR");
    }

    private String deriveCategory(List<String> factors,
                                  FraudAlertEvent alert) {
        boolean hasGeo = factors.contains("GEOLOCATION");
        boolean hasVelocity = factors.contains("VELOCITY");
        boolean hasBehavioral =
                factors.contains("BEHAVIORAL_ANOMALY");
        boolean hasMerchantRisk =
                factors.contains("MERCHANT_RISK");
        boolean hasPolicy =
                factors.contains("POLICY_VIOLATION");

        // Account takeover: impossible travel + behavioral deviation
        // + velocity spike = coordinated attack pattern
        if (hasGeo && hasBehavioral && hasVelocity) {
            return "ACCOUNT_TAKEOVER_SUSPECTED";
        }

        // Card testing: many small transactions, varied merchants
        if (hasVelocity && hasMerchantRisk && alert.amount() != null
                && alert.amount().compareTo(
                SMALL_AMOUNT_THRESHOLD) < 0) {
            return "CARD_TESTING_SUSPECTED";
        }

        // AML: policy citation contains AML prefix
        if (hasPolicy && hasAmlCitation(alert)) {
            return "AML_PATTERN_DETECTED";
        }

        // Geo only: unusual login location, no velocity anomaly
        if (hasGeo && !hasVelocity) {
            return "UNUSUAL_LOCATION";
        }

        // Velocity only
        if (hasVelocity && !hasGeo) {
            return "VELOCITY_ANOMALY";
        }

        return "HIGH_RISK_TRANSACTION";
    }

    private boolean hasAmlCitation(FraudAlertEvent alert) {
        if (alert.fraudDecision() == null) return false;
        List<PolicyCitation> citations =
                alert.fraudDecision().policyCitations();
        if (citations == null) return false;
        return citations.stream()
                .anyMatch(c -> c.policyId() != null &&
                        (c.policyId().startsWith("AML") ||
                                c.policyId().contains("STRUCTURING") ||
                                c.policyId().contains("LAYERING")));
    }
}