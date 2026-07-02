package com.nexus.fraud.lambda.metrics;

import com.nexus.fraud.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fraud Metrics Emitter — custom CloudWatch metrics.
 *
 * Primary metric: fraud.high.severity.count
 * This is the metric the security operations CloudWatch dashboard
 * and the HighSeverityFraudSpike alarm watch.
 *
 * Emitted BEFORE SNS notifications — metrics must never be blocked
 * by notification failures. CloudWatch is always the fastest path.
 *
 * Dimensions: Severity × AlertCategory × Environment
 * → Security dashboard can filter by "show me CRITICAL only"
 * → Alarm fires on total count regardless of dimension
 */
public class FraudMetricsEmitter {

    private static final Logger log =
            LoggerFactory.getLogger(FraudMetricsEmitter.class);

    private final CloudWatchClient cw;
    private final String namespace;
    private final String environment;

    public FraudMetricsEmitter(CloudWatchClient cw,
                               String namespace,
                               String environment) {
        this.cw = cw;
        this.namespace = namespace;
        this.environment = environment;
    }

    public void emit(FraudAlertEvent alert,
                     AlertClassification classification,
                     SarConsiderationResult sar) {

        List<MetricDatum> metrics = new ArrayList<>();
        Instant now = Instant.now();

        // PRIMARY: fraud.high.severity.count
        // The HighSeverityFraudSpike alarm watches this metric.
        // Dimension: Severity × AlertCategory × Environment
        metrics.add(MetricDatum.builder()
                .metricName("fraud.high.severity.count")
                .value(1.0)
                .unit(StandardUnit.COUNT)
                .timestamp(now)
                .dimensions(
                        Dimension.builder()
                                .name("Severity")
                                .value(classification.severity().name())
                                .build(),
                        Dimension.builder()
                                .name("AlertCategory")
                                .value(classification.alertCategory())
                                .build(),
                        Dimension.builder()
                                .name("Environment")
                                .value(environment)
                                .build())
                .build());

        // Risk score distribution
        metrics.add(MetricDatum.builder()
                .metricName("fraud.risk.score")
                .value(alert.riskScore().doubleValue())
                .unit(StandardUnit.NONE)
                .timestamp(now)
                .dimensions(
                        Dimension.builder()
                                .name("AlertCategory")
                                .value(classification.alertCategory())
                                .build(),
                        Dimension.builder()
                                .name("Environment")
                                .value(environment)
                                .build())
                .build());

        // Blocked transaction volume (MXN)
        if (alert.amount() != null) {
            metrics.add(MetricDatum.builder()
                    .metricName("fraud.blocked.amount")
                    .value(alert.amount().doubleValue())
                    .unit(StandardUnit.NONE)
                    .timestamp(now)
                    .dimensions(
                            Dimension.builder()
                                    .name("Currency")
                                    .value(alert.currency() != null
                                            ? alert.currency() : "MXN")
                                    .build())
                    .build());
        }

        // SAR consideration created
        if (sar.required()) {
            metrics.add(MetricDatum.builder()
                    .metricName("fraud.sar.consideration.created")
                    .value(1.0)
                    .unit(StandardUnit.COUNT)
                    .timestamp(now)
                    .dimensions(
                            Dimension.builder()
                                    .name("PatternType")
                                    .value(sar.patternType())
                                    .build())
                    .build());
        }

        // Account action required
        if (classification.requiresAccountAction()) {
            metrics.add(MetricDatum.builder()
                    .metricName("fraud.account.action.required")
                    .value(1.0)
                    .unit(StandardUnit.COUNT)
                    .timestamp(now)
                    .dimensions(
                            Dimension.builder()
                                    .name("RecommendedAction")
                                    .value(classification.recommendedAction())
                                    .build())
                    .build());
        }

        try {
            cw.putMetricData(PutMetricDataRequest.builder()
                    .namespace(namespace)
                    .metricData(metrics)
                    .build());
            log.debug("CloudWatch metrics emitted: count={}",
                    metrics.size());
        } catch (Exception e) {
            // Metrics failure is non-fatal — log and continue
            log.warn("CloudWatch metrics failed (non-fatal): {}",
                    e.getMessage());
        }
    }
}