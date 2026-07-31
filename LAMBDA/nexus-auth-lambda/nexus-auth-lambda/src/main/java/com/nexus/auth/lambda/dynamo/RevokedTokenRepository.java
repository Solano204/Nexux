package com.nexus.auth.lambda.dynamo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;


/**
 * Revoked Token Repository — JWT blacklist in DynamoDB.
 * PK: JTI#{jti}  TTL: JWT's original expiry time.
 */
public class RevokedTokenRepository {

    private static final Logger log =
            LoggerFactory.getLogger(RevokedTokenRepository.class);

    private final DynamoDbClient dynamo;
    private final String tableName;

    public RevokedTokenRepository(DynamoDbClient dynamo,
                           String tableName) {
        this.dynamo = dynamo;
        this.tableName = tableName;
    }

    public boolean isRevoked(String jti) {
        try {
            GetItemResponse response = dynamo.getItem(
                    GetItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of(
                                    "PK", AttributeValue.fromS("JTI#" + jti)))
                            // Revocation is a binary security decision, not
                            // a case that tolerates DynamoDB's default
                            // eventually-consistent read window - this is a
                            // base-table GetItem (not a GSI), so
                            // ConsistentRead is available at 2x RCU cost.
                            .consistentRead(true)
                            .build());
            return response.hasItem();
        } catch (Exception e) {
            log.error("Revocation check failed for jti={}: {}",
                    jti, e.getMessage());
            // On error: fail-safe = treat as NOT revoked
            // (better to serve a valid user than block them)
            return false;
        }
    }

    public void revoke(String jti, String reason) {
        long ttl = Instant.now()
                .plus(3600, ChronoUnit.SECONDS) // 1 hour
                .getEpochSecond();

        dynamo.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "PK", AttributeValue.fromS("JTI#" + jti),
                        "jti", AttributeValue.fromS(jti),
                        "reason", AttributeValue.fromS(reason),
                        "revokedAt", AttributeValue.fromS(
                                Instant.now().toString()),
                        "ttl", AttributeValue.fromN(
                                String.valueOf(ttl))))
                .build());

        log.info("Token revoked: jti={} reason={}", jti, reason);
    }
}