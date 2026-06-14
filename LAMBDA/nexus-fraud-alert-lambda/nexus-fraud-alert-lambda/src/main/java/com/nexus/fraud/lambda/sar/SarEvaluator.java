package com.nexus.fraud.lambda.sar;

import com.nexus.fraud.lambda.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SAR Evaluator — determines if a SAR consideration is required.
 *
 * CNBV (Mexico's financial regulator) requires filing a SAR within
 * 15 calendar days when a suspicious activity meets certain criteria.
 * This evaluator creates the consideration record; the compliance team
 * makes the final decision to file or not.
 *
 * Criteria (any one triggers SAR consideration):
 * 1. Risk score >= sarRiskThreshold (configurable, default 90)
 * 2. AML policy citation in fraud decision
 * 3. Amount in structuring range (MXN 7,000-9,999)
 * 4. Account takeover suspected
 * 5. Fraud service explicitly recommended SAR filing
 */
public class SarEvaluator {

    private static final BigDecimal STRUCTURING_LOWER =
            BigDecimal.valueOf(7_000);
    private static final BigDecimal STRUCTURING_UPPER =
            BigDecimal.valueOf(10_000);

    private final int sarRiskThreshold;
    private final Set<String> sarPatterns;

    public SarEvaluator(int sarRiskThreshold,
                        Set<String> sarPatterns) {
        this.sarRiskThreshold = sarRiskThreshold;
        this.sarPatterns = sarPatterns;
    }

    public SarConsiderationResult evaluate(FraudAlertEvent alert,
                                           AlertClassification classification) {
        List<String> reasons = new ArrayList<>();
        String patternType = "GENERAL_FRAUD";

        // Criterion 1: Risk score threshold
        if (alert.riskScore().compareTo(
                BigDecimal.valueOf(sarRiskThreshold)) >= 0) {
            reasons.add(String.format(
                    "Risk score %.2f >= SAR threshold %d",
                    alert.riskScore(), sarRiskThreshold));
        }

        // Criterion 2: AML policy citation
        boolean hasAmlCitation = hasAmlPolicyCitation(alert);
        if (hasAmlCitation) {
            reasons.add("AML policy citation in fraud decision");
            patternType = "STRUCTURING_OR_LAYERING";
        }

        // Criterion 3: Structuring amount range (MXN 7,000-9,999)
        if (alert.amount() != null
                && alert.amount().compareTo(STRUCTURING_LOWER) >= 0
                && alert.amount().compareTo(STRUCTURING_UPPER) < 0) {
            reasons.add(String.format(
                    "Amount MXN %.2f in structuring range (7,000-9,999)",
                    alert.amount()));
            patternType = "POTENTIAL_STRUCTURING";
        }

        // Criterion 4: Account takeover pattern
        if ("ACCOUNT_TAKEOVER_SUSPECTED"
                .equals(classification.alertCategory())) {
            reasons.add("Account takeover pattern detected");
            patternType = "ACCOUNT_TAKEOVER";
        }

        // Criterion 5: Fraud service explicitly recommended SAR
        String action = alert.recommendedAction();
        if ("FILE_SAR".equals(action) ||
                "ESCALATE_TO_COMPLIANCE".equals(action)) {
            reasons.add("Fraud service recommended: " + action);
        }

        if (reasons.isEmpty()) {
            return SarConsiderationResult.notRequired();
        }

        return new SarConsiderationResult(
                true,
                UUID.randomUUID().toString(),
                String.join("; ", reasons),
                patternType
        );
    }

    private boolean hasAmlPolicyCitation(FraudAlertEvent alert) {
        if (alert.fraudDecision() == null) return false;
        var citations = alert.fraudDecision().policyCitations();
        if (citations == null) return false;
        return citations.stream().anyMatch(c ->
                c.policyId() != null && (
                        c.policyId().startsWith("AML") ||
                                c.policyId().contains("STRUCTURING") ||
                                c.policyId().contains("LAYERING") ||
                                sarPatterns.stream().anyMatch(p ->
                                        c.policyId().contains(p))));
    }
}