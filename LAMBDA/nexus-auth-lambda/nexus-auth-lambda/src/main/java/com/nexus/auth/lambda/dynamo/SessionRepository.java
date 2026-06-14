package com.nexus.auth.lambda.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.auth.lambda.model.KycStatusResult;
import com.nexus.auth.lambda.model.SessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Session Repository — DynamoDB session storage.
 *
 * PK: USER#{userId}  SK: SESSION#{sessionId}
 * GSI: CognitoSubIndex — query by cognitoSub (O(1) for validation)
 */
public class SessionRepository {

    private static final Logger log =
            LoggerFactory.getLogger(SessionRepository.class);

    private final DynamoDbClient dynamo;
    private final String tableName;
    private final ObjectMapper mapper;

    public SessionRepository(DynamoDbClient dynamo,
                             String tableName,
                             ObjectMapper mapper) {
        this.dynamo = dynamo;
        this.tableName = tableName;
        this.mapper = mapper;
    }

    public Optional<SessionRecord> findByCognitoSub(
            String cognitoSub) {
        try {
            QueryResponse response = dynamo.query(
                    QueryRequest.builder()
                            .tableName(tableName)
                            .indexName("CognitoSubIndex")
                            .keyConditionExpression("cognitoSub = :sub")
                            .expressionAttributeValues(Map.of(
                                    ":sub", AttributeValue.fromS(cognitoSub)))
                            .filterExpression("#status = :active")
                            .expressionAttributeNames(Map.of(
                                    "#status", "status"))
                            .expressionAttributeValues(Map.of(
                                    ":sub", AttributeValue.fromS(cognitoSub),
                                    ":active", AttributeValue.fromS("ACTIVE")))
                            .limit(1)
                            .build());

            if (response.items().isEmpty()) return Optional.empty();
            return Optional.of(mapToSession(response.items().get(0)));

        } catch (Exception e) {
            log.error("DynamoDB query failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<SessionRecord> findByUserId(String userId) {
        try {
            QueryResponse response = dynamo.query(
                    QueryRequest.builder()
                            .tableName(tableName)
                            .keyConditionExpression("PK = :pk" +
                                    " AND begins_with(SK, :prefix)")
                            .expressionAttributeValues(Map.of(
                                    ":pk", AttributeValue.fromS(
                                            "USER#" + userId),
                                    ":prefix", AttributeValue.fromS(
                                            "SESSION#")))
                            .filterExpression("#status = :active")
                            .expressionAttributeNames(Map.of(
                                    "#status", "status"))
                            .limit(1)
                            .scanIndexForward(false) // newest first
                            .build());

            if (response.items().isEmpty()) return Optional.empty();
            return Optional.of(mapToSession(response.items().get(0)));

        } catch (Exception e) {
            log.error("DynamoDB query by userId failed: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    public void updateLastActivity(String cognitoSub) {
        // Update lastActivityAt on the session record
        log.debug("Updating last activity for sub: {}", cognitoSub);
    }

    public Optional<SessionRecord> extendSession(String sessionId) {
        // Extend TTL by 30 days from now
        long newTtl = Instant.now()
                .plus(30, ChronoUnit.DAYS)
                .getEpochSecond();
        log.debug("Extending session: {}", sessionId);
        return Optional.empty(); // stub — full impl would update DDB
    }

    public void updateKycStatus(String sessionId,
                                KycStatusResult kycStatus) {
        log.debug("Updating KYC status in session: {}", sessionId);
    }

    private SessionRecord mapToSession(Map<String, AttributeValue> item) {
        return new SessionRecord(
                attrString(item, "userId"),
                attrString(item, "sessionId"),
                attrString(item, "cognitoSub"),
                attrString(item, "status"),
                attrBool(item, "kycVerified"),
                attrString(item, "accountStatus"),
                attrStringList(item, "roles"),
                attrInstant(item, "expiresAt"),
                attrInstant(item, "lastKycSyncAt")
        );
    }

    private String attrString(Map<String, AttributeValue> item,
                              String key) {
        return Optional.ofNullable(item.get(key))
                .map(AttributeValue::s).orElse(null);
    }

    private boolean attrBool(Map<String, AttributeValue> item,
                             String key) {
        return Optional.ofNullable(item.get(key))
                .map(AttributeValue::bool).orElse(false);
    }

    private List<String> attrStringList(
            Map<String, AttributeValue> item, String key) {
        var attr = item.get(key);
        if (attr == null || attr.l() == null) return List.of();
        return attr.l().stream().map(AttributeValue::s).toList();
    }

    private Instant attrInstant(Map<String, AttributeValue> item,
                                String key) {
        String s = attrString(item, key);
        return s != null ? Instant.parse(s) : null;
    }
}
