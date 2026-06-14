package com.nexus.fraud.lambda.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fraud Alert Repository — immutable DynamoDB forensic record.
 *
 * WRITE ONCE design:
 * - Initial write uses conditionExpression attribute_not_exists(PK)
 * - If alertId already exists → ConditionalCheckFailed → skip (idempotent)
 * - Notification status appended via UpdateItem after SNS publishes
 * - Core fraud decision fields NEVER overwritten after initial write
 *
 * 7-year TTL: CNBV fraud record retention requirement.
 * KMS encryption: configured at table level in template.yaml.
 * PITR: any record recoverable to any second in last 35 days.
 *
 * GSI keys:
 *   UserIndex   → compliance can query all alerts for user X
 *   DateIndex   → daily compliance review "what happened today"
 *   StatusIndex → compliance queue "what needs review right now"
 */
public class FraudAlertRepository {

    private static final Logger log =
            LoggerFactory.getLogger(FraudAlertRepository.class);

    private static final long SEVEN_YEARS_SECONDS  = 7L * 365 * 86_400;
    private static final long FIFTEEN_DAYS_SECONDS = 15L * 86_400;

    private final DynamoDbClient dynamo;
    private final String alertsTable;
    private final String sarTable;
    private final ObjectMapper mapper;

    public FraudAlertRepository(DynamoDbClient dynamo,
                                String alertsTable,
                                String sarTable,
                                ObjectMapper mapper) {
        this.dynamo = dynamo;
        this.alertsTable = alertsTable;
        this.sarTable = sarTable;
        this.mapper = mapper;
    }

    /**
     * Returns true if the alert already exists (idempotency check).
     * Reads only the processingStatus projection — minimal read cost.
     */
    public boolean alertExists(String alertId) {
        if (alertId == null || alertId.isBlank()) return false;
        try {
            GetItemResponse response = dynamo.getItem(
                    GetItemRequest.builder()
                            .tableName(alertsTable)
                            .key(Map.of(
                                    "PK", AttributeValue.fromS(
                                            "ALERT#" + alertId),
                                    "SK", AttributeValue.fromS("METADATA")))
                            .projectionExpression("processingStatus")
                            .build());
            return response.hasItem();
        } catch (Exception e) {
            log.warn("Idempotency check failed: alertId={} — " +
                    "proceeding (fail-open)", alertId, e);
            return false;
        }
    }

    /**
     * Write the initial fraud alert record.
     * Returns the alertId (generated if not present in event).
     *
     * conditionExpression: attribute_not_exists(PK)
     * → Safe for Kafka/SQS at-least-once redelivery.
     * → ConditionalCheckFailed = already stored = no-op.
     */
    public String storeAlert(FraudAlertEvent alert,
                             AlertClassification classification,
                             String sqsMessageId) {

        String alertId = (alert.alertId() != null &&
                !alert.alertId().isBlank())
                ? alert.alertId()
                : UUID.randomUUID().toString();

        Instant now   = Instant.now();
        String today  = now.atZone(ZoneOffset.UTC)
                .toLocalDate().toString();
        long ttl      = now.plusSeconds(SEVEN_YEARS_SECONDS)
                .getEpochSecond();

        Map<String, AttributeValue> item = new HashMap<>();

        // Primary key
        item.put("PK", av("ALERT#" + alertId));
        item.put("SK", av("METADATA"));

        // GSI keys
        item.put("GSI_USER_PK",   av("USER#" + alert.userId()));
        item.put("GSI_USER_SK",   av(alertId));
        item.put("GSI_DATE_PK",   av("DATE#" + today));
        item.put("GSI_DATE_SK",   av(alertId));
        item.put("GSI_STATUS_PK", av("STATUS#PENDING_REVIEW"));
        item.put("GSI_STATUS_SK", av(now.toString()));

        // Identity
        item.put("alertId",       av(alertId));
        item.put("transactionId", av(alert.transactionId()));
        item.put("userId",        av(alert.userId()));
        item.put("sourceAccountId", av(alert.sourceAccountId()));
        item.put("targetAccountId",
                av(alert.targetAccountId() != null
                        ? alert.targetAccountId() : ""));

        // Financial
        item.put("amount",          avn(alert.amount().toPlainString()));
        item.put("currency",        av(alert.currency()));
        item.put("transactionType", av(alert.transactionType()));

        // Fraud decision as JSON
        String fraudDecisionJson = "{\"error\":\"unavailable\"}";
        try {
            fraudDecisionJson = mapper.writeValueAsString(
                    alert.fraudDecision());
        } catch (JsonProcessingException ignored) {}
        item.put("fraudDecision", av(fraudDecisionJson));

        // Classification
        item.put("severity",       av(classification.severity().name()));
        item.put("alertCategory",  av(classification.alertCategory()));
        item.put("riskScore",      avn(alert.riskScore().toPlainString()));
        item.put("recommendedAction",
                av(classification.recommendedAction()));
        item.put("requiresAccountAction",
                AttributeValue.fromBool(
                        classification.requiresAccountAction()));

        // Processing provenance
        item.put("processingStatus",  av("PROCESSING"));
        item.put("lambdaProcessedAt", av(now.toString()));
        item.put("sqsMessageId",      av(sqsMessageId));
        item.put("sourceService",
                av(alert.sourceService() != null
                        ? alert.sourceService() : "nexus-fraud-service"));
        item.put("sourcePlane", av("LOCAL"));
        item.put("traceId",
                av(alert.traceId() != null ? alert.traceId() : ""));

        // Review workflow + timestamps
        item.put("reviewStatus", av("PENDING_REVIEW"));
        item.put("createdAt",    av(now.toString()));
        item.put("ttl",          avn(String.valueOf(ttl)));

        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(alertsTable)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
            log.info("Fraud alert stored: alertId={} severity={} " +
                            "category={} riskScore={}",
                    alertId, classification.severity(),
                    classification.alertCategory(),
                    alert.riskScore());
        } catch (ConditionalCheckFailedException e) {
            log.info("Alert already in DynamoDB (idempotent): " +
                    "alertId={}", alertId);
        }

        return alertId;
    }

    /**
     * After SNS publish: update alert with notification confirmations.
     * Uses UpdateItem (not PutItem) so the core fraud decision is
     * never overwritten.
     */
    public void updateWithNotificationStatus(
            String alertId,
            String complianceMsgId,
            String opsMsgId,
            boolean sarRequired,
            String sarId) {

        StringBuilder updateExpr = new StringBuilder(
                "SET processingStatus = :done, "
                        + "notificationsSentAt = :now");

        Map<String, AttributeValue> vals = new HashMap<>();
        vals.put(":done", av("PROCESSED"));
        vals.put(":now",  av(Instant.now().toString()));

        if (complianceMsgId != null) {
            updateExpr.append(", complianceSnsMessageId = :cid");
            vals.put(":cid", av(complianceMsgId));
        }
        if (opsMsgId != null) {
            updateExpr.append(", operationsSnsMessageId = :oid");
            vals.put(":oid", av(opsMsgId));
        }
        if (sarRequired && sarId != null) {
            updateExpr.append(", sarRequired = :sar, sarId = :sid");
            vals.put(":sar", AttributeValue.fromBool(true));
            vals.put(":sid", av(sarId));
        }
        updateExpr.append(", cloudWatchMetricEmitted = :cw");
        vals.put(":cw", AttributeValue.fromBool(true));

        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(alertsTable)
                    .key(Map.of(
                            "PK", av("ALERT#" + alertId),
                            "SK", av("METADATA")))
                    .updateExpression(updateExpr.toString())
                    .expressionAttributeValues(vals)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to update alert notification status: " +
                    "alertId={}", alertId, e);
        }
    }

    /**
     * Create a SAR consideration record with CNBV 15-day deadline.
     *
     * Uses HashMap instead of Map.of() — SAR item has 14 fields,
     * which exceeds Map.of()'s 10-entry hard limit.
     */
    public void createSarConsideration(FraudAlertEvent alert,
                                       String alertId,
                                       SarConsiderationResult sar) {
        Instant now      = Instant.now();
        Instant deadline = now.plusSeconds(FIFTEEN_DAYS_SECONDS);
        long ttl         = now.plusSeconds(SEVEN_YEARS_SECONDS)
                .getEpochSecond();

        // HashMap required: Map.of() only supports up to 10 entries
        Map<String, AttributeValue> sarItem = new HashMap<>();
        sarItem.put("PK",                   av("SAR#" + sar.sarId()));
        sarItem.put("SK",                   av("METADATA"));
        sarItem.put("sarId",                av(sar.sarId()));
        sarItem.put("alertId",              av(alertId));
        sarItem.put("transactionId",        av(alert.transactionId()));
        sarItem.put("userId",               av(alert.userId()));
        sarItem.put("considerationReason",  av(sar.reason()));
        sarItem.put("patternType",          av(sar.patternType()));
        sarItem.put("sarThresholdMet",
                AttributeValue.fromBool(true));
        sarItem.put("automaticConsideration",
                AttributeValue.fromBool(true));
        sarItem.put("status",
                av("PENDING_COMPLIANCE_REVIEW"));
        sarItem.put("regulatoryDeadline",   av(deadline.toString()));
        sarItem.put("createdAt",            av(now.toString()));
        sarItem.put("ttl",                  avn(String.valueOf(ttl)));

        dynamo.putItem(PutItemRequest.builder()
                .tableName(sarTable)
                .item(sarItem)
                .build());

        log.info("SAR consideration created: sarId={} alertId={} " +
                "deadline={}", sar.sarId(), alertId, deadline);
    }

    private AttributeValue av(String s) {
        return AttributeValue.fromS(s != null ? s : "");
    }

    private AttributeValue avn(String n) {
        return AttributeValue.fromN(n);
    }
}