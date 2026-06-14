package com.nexus.fraud.lambda.notification;

import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fraud Alert Notifier — SNS publish to compliance + security ops.
 *
 * Two separate topics:
 *
 * 1. Compliance topic (nexus-fraud-compliance-alerts):
 *    - Narrative, human-readable message for compliance officers
 *    - Includes: SAR deadline, review URL, reasoning, factor summary
 *    - Subscribed: compliance team email, mobile
 *
 * 2. Security ops topic (nexus-security-operations):
 *    - Technical, structured message for security engineers
 *    - Includes: tool call summary, trace ID, triggering evidence
 *    - Subscribed: security operations webhook, on-call rotation
 *
 * Both publishes are attempted independently.
 * Failure of one does NOT prevent the other.
 * Both message IDs are returned for storage in DynamoDB.
 */
public class FraudAlertNotifier {

    private static final Logger log =
            LoggerFactory.getLogger(FraudAlertNotifier.class);

    private final SnsClient sns;
    private final String complianceTopicArn;
    private final String opsTopicArn;
    private final ObjectMapper mapper;
    private final String environment;

    public FraudAlertNotifier(SnsClient sns,
                              String complianceTopicArn,
                              String opsTopicArn,
                              ObjectMapper mapper,
                              String environment) {
        this.sns = sns;
        this.complianceTopicArn = complianceTopicArn;
        this.opsTopicArn = opsTopicArn;
        this.mapper = mapper;
        this.environment = environment;
    }

    public String notifyCompliance(FraudAlertEvent alert,
                                   AlertClassification classification,
                                   SarConsiderationResult sar,
                                   String alertId) {

        var segment = AWSXRay.beginSubsegment("SNS.ComplianceAlert");
        try {
            ComplianceAlertMessage message = new ComplianceAlertMessage(
                    alertId,
                    classification.severity().name(),
                    classification.alertCategory(),
                    alert.transactionId(),
                    alert.userId(),
                    alert.amount(),
                    alert.currency(),
                    alert.riskScore(),
                    buildFactorSummary(alert.triggeringFactors()),
                    truncate(alert.fraudDecision() != null
                            ? alert.fraudDecision().reasoning() : "", 500),
                    classification.recommendedAction(),
                    sar.required(),
                    sar.sarId(),
                    sar.required()
                            ? Instant.now().plusSeconds(15 * 86_400)
                            .toString() : null,
                    "https://compliance.nexusbank.com/alerts/" + alertId,
                    Instant.now().toString()
            );

            String body;
            try {
                body = mapper.writeValueAsString(message);
            } catch (JsonProcessingException e) {
                body = buildFallbackBody(alert, classification);
            }

            String subject = buildSubject(alert, classification);

            PublishResponse response = sns.publish(
                    PublishRequest.builder()
                            .topicArn(complianceTopicArn)
                            .subject(subject)
                            .message(body)
                            .messageAttributes(Map.of(
                                    "severity",
                                    snsAttr(classification.severity().name()),
                                    "alertCategory",
                                    snsAttr(classification.alertCategory()),
                                    "sarRequired",
                                    snsAttr(String.valueOf(sar.required())),
                                    "environment",
                                    snsAttr(environment)))
                            .build());

            log.info("Compliance notified: alertId={} msgId={} " +
                            "sarRequired={}", alertId,
                    response.messageId(), sar.required());

            return response.messageId();

        } catch (Exception e) {
            segment.addException(e);
            log.error("Compliance SNS publish failed: alertId={} {}",
                    alertId, e.getMessage());
            return null;
        } finally {
            AWSXRay.endSubsegment();
        }
    }

    public String notifySecurityOps(FraudAlertEvent alert,
                                    AlertClassification classification,
                                    String alertId) {

        var segment = AWSXRay.beginSubsegment("SNS.SecurityOpsAlert");
        try {
            SecurityOpsMessage message = new SecurityOpsMessage(
                    alertId,
                    alert.transactionId(),
                    alert.userId(),
                    alert.riskScore(),
                    classification.severity().name(),
                    classification.alertCategory(),
                    classification.recommendedAction(),
                    alert.fraudDecision() != null
                            ? alert.fraudDecision().toolCallSummary()
                            : Map.of(),
                    alert.triggeringFactors() != null
                            ? alert.triggeringFactors().stream()
                            .map(f -> f.category() + ": " + f.evidence())
                            .toList()
                            : List.of(),
                    alert.traceId(),
                    Instant.now().toString()
            );

            String body;
            try {
                body = mapper.writeValueAsString(message);
            } catch (JsonProcessingException e) {
                body = "{\"alertId\":\"" + alertId + "\"}";
            }

            PublishResponse response = sns.publish(
                    PublishRequest.builder()
                            .topicArn(opsTopicArn)
                            .subject("FRAUD ALERT: " +
                                    classification.alertCategory() +
                                    " | Score: " + alert.riskScore())
                            .message(body)
                            .messageAttributes(Map.of(
                                    "severity",
                                    snsAttr(classification.severity().name()),
                                    "requiresAccountAction",
                                    snsAttr(String.valueOf(
                                            classification.requiresAccountAction()))))
                            .build());

            log.info("Security ops notified: alertId={} msgId={}",
                    alertId, response.messageId());
            return response.messageId();

        } catch (Exception e) {
            segment.addException(e);
            log.error("Security ops SNS failed: alertId={} {}",
                    alertId, e.getMessage());
            return null;
        } finally {
            AWSXRay.endSubsegment();
        }
    }

    private String buildSubject(FraudAlertEvent alert,
                                AlertClassification classification) {
        String prefix = switch (classification.severity()) {
            case CRITICAL -> "🚨 CRITICAL FRAUD ALERT";
            case HIGH     -> "⚠️ HIGH SEVERITY FRAUD ALERT";
            default       -> "⚡ ELEVATED FRAUD ALERT";
        };
        String userId = alert.userId().length() >= 8
                ? alert.userId().substring(0, 8) + "..." : alert.userId();
        return String.format("%s — %s — MXN %.0f — %s",
                prefix,
                classification.alertCategory().replace('_', ' '),
                alert.amount(), userId);
    }

    private String buildFactorSummary(List<TriggeringFactor> factors) {
        if (factors == null || factors.isEmpty()) return "No factors";
        return factors.stream()
                .sorted(Comparator.comparing(TriggeringFactor::weight)
                        .reversed())
                .limit(3)
                .map(f -> String.format("• %s (%.0f%%): %s",
                        f.category(),
                        f.weight().multiply(BigDecimal.valueOf(100)),
                        f.description()))
                .collect(Collectors.joining("\n"));
    }

    private String buildFallbackBody(FraudAlertEvent alert,
                                     AlertClassification classification) {
        return String.format(
                "{\"alertId\":\"%s\",\"riskScore\":%.2f," +
                        "\"severity\":\"%s\",\"category\":\"%s\"," +
                        "\"userId\":\"%s\",\"amount\":%.2f}",
                alert.alertId(), alert.riskScore(),
                classification.severity(),
                classification.alertCategory(),
                alert.userId(), alert.amount());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private MessageAttributeValue snsAttr(String v) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(v != null ? v : "UNKNOWN")
                .build();
    }
}