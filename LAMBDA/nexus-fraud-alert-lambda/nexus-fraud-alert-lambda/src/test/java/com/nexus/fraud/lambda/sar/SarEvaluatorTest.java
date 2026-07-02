package com.nexus.fraud.lambda.sar;

import com.nexus.fraud.lambda.model.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tag("unit")
class SarEvaluatorTest {

    SarEvaluator evaluator = new SarEvaluator(90,
        Set.of("STRUCTURING", "LAYERING", "ACCOUNT_TAKEOVER", "AML"));

    @Test
    @DisplayName("Score >= 90 triggers SAR consideration")
    void evaluate_highScore_sarRequired() {
        var alert = buildAlert(new BigDecimal("92"), new BigDecimal("5000"));
        var classification = new AlertClassification(
            AlertSeverity.HIGH, "VELOCITY_ANOMALY", false, "MONITOR");

        var result = evaluator.evaluate(alert, classification);

        assertThat(result.required()).isTrue();
        assertThat(result.sarId()).isNotNull();
        assertThat(result.reason()).contains("SAR threshold");
    }

    @Test
    @DisplayName("Score < 90 without other criteria = no SAR")
    void evaluate_lowScore_noSar() {
        var alert = buildAlert(new BigDecimal("87"), new BigDecimal("5000"));
        var classification = new AlertClassification(
            AlertSeverity.ELEVATED, "VELOCITY_ANOMALY", false, "MONITOR");

        var result = evaluator.evaluate(alert, classification);

        assertThat(result.required()).isFalse();
    }

    @Test
    @DisplayName("Amount in structuring range triggers SAR")
    void evaluate_structuringAmount_sarRequired() {
        var alert = buildAlert(new BigDecimal("87"), new BigDecimal("8500"));
        var classification = new AlertClassification(
            AlertSeverity.ELEVATED, "VELOCITY_ANOMALY", false, "MONITOR");

        var result = evaluator.evaluate(alert, classification);

        assertThat(result.required()).isTrue();
        assertThat(result.patternType()).isEqualTo("POTENTIAL_STRUCTURING");
    }

    @Test
    @DisplayName("Account takeover pattern triggers SAR")
    void evaluate_accountTakeover_sarRequired() {
        var alert = buildAlert(new BigDecimal("87"), new BigDecimal("5000"));
        var classification = new AlertClassification(
            AlertSeverity.ELEVATED, "ACCOUNT_TAKEOVER_SUSPECTED",
            true, "BLOCK_ACCOUNT_TEMPORARY");

        var result = evaluator.evaluate(alert, classification);

        assertThat(result.required()).isTrue();
        assertThat(result.patternType()).isEqualTo("ACCOUNT_TAKEOVER");
    }

    @Test
    @DisplayName("FILE_SAR recommendation triggers SAR")
    void evaluate_fileSarAction_sarRequired() {
        var alert = buildAlertWithAction(new BigDecimal("87"),
            new BigDecimal("5000"), "FILE_SAR");
        var classification = new AlertClassification(
            AlertSeverity.ELEVATED, "HIGH_RISK_TRANSACTION",
            false, "FILE_SAR");

        var result = evaluator.evaluate(alert, classification);

        assertThat(result.required()).isTrue();
        assertThat(result.reason()).contains("FILE_SAR");
    }

    @Test
    @DisplayName("AML policy citation triggers SAR")
    void evaluate_amlCitation_sarRequired() {
        var citations = List.of(
            new PolicyCitation("AML-4.2", "Anti-Money Laundering", "Applied"));
        var decision = new FraudDecisionSummary("REJECT",
            new BigDecimal("87"), new BigDecimal("0.9"),
            "reason", citations, Map.of());
        var factors = List.of(new TriggeringFactor(
            "POLICY_VIOLATION", "AML pattern", new BigDecimal("0.5"), "evidence"));
        var alert = new FraudAlertEvent("a1", "t1", "u1", "src", "tgt",
            new BigDecimal("5000"), "MXN", "PAYMENT",
            new BigDecimal("87"), null, "MONITOR",
            "nexus-fraud-service", "trace", decision, factors);
        var classification = new AlertClassification(
            AlertSeverity.ELEVATED, "AML_PATTERN_DETECTED", false, "MONITOR");

        var result = evaluator.evaluate(alert, classification);

        assertThat(result.required()).isTrue();
        assertThat(result.patternType()).isEqualTo("STRUCTURING_OR_LAYERING");
    }

    private FraudAlertEvent buildAlert(BigDecimal score, BigDecimal amount) {
        return buildAlertWithAction(score, amount, "MONITOR");
    }

    private FraudAlertEvent buildAlertWithAction(BigDecimal score,
                                                   BigDecimal amount,
                                                   String action) {
        var factors = List.of(new TriggeringFactor(
            "VELOCITY", "desc", new BigDecimal("0.3"), "evidence"));
        return new FraudAlertEvent("a1", "t1", "u1", "src", "tgt",
            amount, "MXN", "PAYMENT", score, null, action,
            "nexus-fraud-service", "trace",
            new FraudDecisionSummary("REJECT", score,
                new BigDecimal("0.95"), "reasoning",
                List.of(), Map.of()),
            factors);
    }
}
