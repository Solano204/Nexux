package com.nexus.fraud.lambda;

import com.nexus.fraud.lambda.model.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for FraudAlertHandler domain logic.
 * Tests classification and SAR evaluation without AWS SDK dependencies.
 * Integration tests with DynamoDB/SNS require LocalStack or SAM local.
 */
@Tag("unit")
class FraudAlertHandlerTest {

    @Test
    @DisplayName("AlertSeverity.fromScore maps correctly")
    void alertSeverity_scoreMapping() {
        assertThat(AlertSeverity.fromScore(new BigDecimal("97")))
            .isEqualTo(AlertSeverity.CRITICAL);
        assertThat(AlertSeverity.fromScore(new BigDecimal("95")))
            .isEqualTo(AlertSeverity.CRITICAL);
        assertThat(AlertSeverity.fromScore(new BigDecimal("92")))
            .isEqualTo(AlertSeverity.HIGH);
        assertThat(AlertSeverity.fromScore(new BigDecimal("90")))
            .isEqualTo(AlertSeverity.HIGH);
        assertThat(AlertSeverity.fromScore(new BigDecimal("87")))
            .isEqualTo(AlertSeverity.ELEVATED);
        assertThat(AlertSeverity.fromScore(new BigDecimal("85")))
            .isEqualTo(AlertSeverity.ELEVATED);
    }

    @Test
    @DisplayName("SarConsiderationResult.notRequired returns false")
    void sarResult_notRequired() {
        var result = SarConsiderationResult.notRequired();
        assertThat(result.required()).isFalse();
        assertThat(result.sarId()).isNull();
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("FraudAlertEvent record accessors work")
    void fraudAlertEvent_accessors() {
        var factors = List.of(new TriggeringFactor(
            "VELOCITY", "5 txns in 4min",
            new BigDecimal("0.45"), "velocity5min=5"));
        var decision = new FraudDecisionSummary(
            "REJECT", new BigDecimal("94"), new BigDecimal("0.97"),
            "High fraud indicators",
            List.of(new PolicyCitation("AML-1", "Title", "Applied")),
            Map.of("velocityCheck", "ANOMALOUS"));

        var alert = new FraudAlertEvent(
            "alert-1", "txn-1", "user-1", "acc-src", "acc-tgt",
            new BigDecimal("45000"), "MXN", "EXTERNAL_TRANSFER",
            new BigDecimal("94"), "ACCOUNT_TAKEOVER_SUSPECTED",
            "BLOCK_ACCOUNT_TEMPORARY",
            "nexus-fraud-service", "trace-123",
            decision, factors);

        assertThat(alert.alertId()).isEqualTo("alert-1");
        assertThat(alert.riskScore()).isEqualByComparingTo("94");
        assertThat(alert.amount()).isEqualByComparingTo("45000");
        assertThat(alert.fraudDecision().outcome()).isEqualTo("REJECT");
        assertThat(alert.fraudDecision().policyCitations()).hasSize(1);
        assertThat(alert.triggeringFactors()).hasSize(1);
        assertThat(alert.triggeringFactors().get(0).category())
            .isEqualTo("VELOCITY");
    }

    @Test
    @DisplayName("ComplianceAlertMessage constructs correctly")
    void complianceAlertMessage_construction() {
        var msg = new ComplianceAlertMessage(
            "alert-1", "CRITICAL", "ACCOUNT_TAKEOVER_SUSPECTED",
            "txn-1", "user-1", new BigDecimal("45000"), "MXN",
            new BigDecimal("94"), "factor summary", "reasoning",
            "BLOCK_ACCOUNT_TEMPORARY", true, "sar-1",
            "2025-05-22T14:30:00Z",
            "https://compliance.nexusbank.com/alerts/alert-1",
            "2025-05-07T14:30:00Z");

        assertThat(msg.alertId()).isEqualTo("alert-1");
        assertThat(msg.sarRequired()).isTrue();
        assertThat(msg.regulatoryDeadline()).contains("2025-05-22");
    }

    @Test
    @DisplayName("SecurityOpsMessage constructs correctly")
    void securityOpsMessage_construction() {
        var msg = new SecurityOpsMessage(
            "alert-1", "txn-1", "user-1",
            new BigDecimal("94"), "CRITICAL",
            "ACCOUNT_TAKEOVER_SUSPECTED",
            "BLOCK_ACCOUNT_TEMPORARY",
            Map.of("velocityCheck", "ANOMALOUS"),
            List.of("VELOCITY: 5 txns in 4min"),
            "trace-123", "2025-05-07T14:30:00Z");

        assertThat(msg.alertId()).isEqualTo("alert-1");
        assertThat(msg.toolCallSummary()).containsKey("velocityCheck");
        assertThat(msg.triggeringFactors()).hasSize(1);
    }
}
