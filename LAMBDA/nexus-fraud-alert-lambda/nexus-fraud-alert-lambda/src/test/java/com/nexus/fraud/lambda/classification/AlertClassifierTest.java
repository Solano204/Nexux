package com.nexus.fraud.lambda.classification;

import com.nexus.fraud.lambda.model.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag("unit")
class AlertClassifierTest {

    AlertClassifier classifier = new AlertClassifier();

    @Test
    @DisplayName("Geo + behavioral + velocity = account takeover")
    void classify_geoVelocityBehavioral_accountTakeover() {
        var alert = buildAlert(new BigDecimal("94"),
            "GEOLOCATION", "BEHAVIORAL_ANOMALY", "VELOCITY");
        var result = classifier.classify(alert);
        assertThat(result.alertCategory())
            .isEqualTo("ACCOUNT_TAKEOVER_SUSPECTED");
        assertThat(result.severity()).isEqualTo(AlertSeverity.HIGH);
    }

    @Test
    @DisplayName("Velocity + merchant risk + small amount = card testing")
    void classify_velocityMerchantSmallAmount_cardTesting() {
        var alert = buildAlertWithAmount(new BigDecimal("94"),
            new BigDecimal("150"),
            "VELOCITY", "MERCHANT_RISK");
        var result = classifier.classify(alert);
        assertThat(result.alertCategory())
            .isEqualTo("CARD_TESTING_SUSPECTED");
    }

    @Test
    @DisplayName("Score >= 95 = CRITICAL severity")
    void classify_score95_criticalSeverity() {
        var alert = buildAlert(new BigDecimal("97"), "VELOCITY");
        var result = classifier.classify(alert);
        assertThat(result.severity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    @Test
    @DisplayName("Score 90-94 = HIGH severity")
    void classify_score92_highSeverity() {
        var alert = buildAlert(new BigDecimal("92"), "VELOCITY");
        var result = classifier.classify(alert);
        assertThat(result.severity()).isEqualTo(AlertSeverity.HIGH);
    }

    @Test
    @DisplayName("Score 85-89 = ELEVATED severity")
    void classify_score87_elevatedSeverity() {
        var alert = buildAlert(new BigDecimal("87"), "VELOCITY");
        var result = classifier.classify(alert);
        assertThat(result.severity()).isEqualTo(AlertSeverity.ELEVATED);
    }

    @Test
    @DisplayName("BLOCK_ACCOUNT_TEMPORARY = requiresAccountAction")
    void classify_blockAccount_requiresAction() {
        var alert = buildAlertWithAction(new BigDecimal("94"),
            "BLOCK_ACCOUNT_TEMPORARY", "VELOCITY");
        var result = classifier.classify(alert);
        assertThat(result.requiresAccountAction()).isTrue();
    }

    @Test
    @DisplayName("Geo only (no velocity) = unusual location")
    void classify_geoOnly_unusualLocation() {
        var alert = buildAlert(new BigDecimal("88"), "GEOLOCATION");
        var result = classifier.classify(alert);
        assertThat(result.alertCategory()).isEqualTo("UNUSUAL_LOCATION");
    }

    @Test
    @DisplayName("Velocity only = velocity anomaly")
    void classify_velocityOnly_velocityAnomaly() {
        var alert = buildAlert(new BigDecimal("90"), "VELOCITY");
        var result = classifier.classify(alert);
        assertThat(result.alertCategory()).isEqualTo("VELOCITY_ANOMALY");
    }

    @Test
    @DisplayName("No specific pattern = high risk transaction")
    void classify_noPattern_highRisk() {
        var alert = buildAlert(new BigDecimal("88"), "OTHER_FACTOR");
        var result = classifier.classify(alert);
        assertThat(result.alertCategory()).isEqualTo("HIGH_RISK_TRANSACTION");
    }

    private FraudAlertEvent buildAlert(BigDecimal score, String... categories) {
        return buildAlertWithAmount(score, new BigDecimal("5000"), categories);
    }

    private FraudAlertEvent buildAlertWithAmount(BigDecimal score,
                                                   BigDecimal amount,
                                                   String... categories) {
        return buildAlertFull(score, amount, "MONITOR", categories);
    }

    private FraudAlertEvent buildAlertWithAction(BigDecimal score,
                                                   String action,
                                                   String... categories) {
        return buildAlertFull(score, new BigDecimal("5000"), action, categories);
    }

    private FraudAlertEvent buildAlertFull(BigDecimal score, BigDecimal amount,
                                             String action, String... categories) {
        var factors = java.util.Arrays.stream(categories)
            .map(c -> new TriggeringFactor(c, "desc",
                new BigDecimal("0.3"), "evidence"))
            .toList();
        return new FraudAlertEvent(
            "alert-1", "txn-1", "user-1",
            "acc-src", "acc-tgt",
            amount, "MXN", "PAYMENT",
            score, null, action,
            "nexus-fraud-service", "trace-1",
            new FraudDecisionSummary("REJECT", score,
                new BigDecimal("0.95"), "reasoning",
                List.of(), Map.of()),
            factors);
    }
}
