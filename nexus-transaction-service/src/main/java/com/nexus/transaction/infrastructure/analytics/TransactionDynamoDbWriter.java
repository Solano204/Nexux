package com.nexus.transaction.infrastructure.analytics;

import com.nexus.transaction.domain.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes terminal-state transaction snapshots to nexus-transactions DynamoDB table.
 * Provides the event source that triggers nexus-analytics-aggregator-lambda
 * via DynamoDB Streams (NEW_AND_OLD_IMAGES).
 *
 * Write pattern: single PutItem at each terminal transition (COMPLETED, FAILED,
 * REVERSED). The stream record's OldImage/NewImage lets the aggregator detect the
 * correct transition. FAILED inserts (no prior item) are handled by the fixed
 * transition_detector.py.
 *
 * All writes are non-fatal: a DynamoDB failure must never affect the financial
 * transaction itself.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nexus.analytics.dynamodb.enabled",
        havingValue = "true", matchIfMissing = false)
public class TransactionDynamoDbWriter {

    private final DynamoDbClient dynamo;
    private final String tableName;

    public TransactionDynamoDbWriter(
            DynamoDbClient dynamoDbClient,
            @Value("${nexus.analytics.dynamodb.table:nexus-transactions}") String tableName) {
        this.dynamo = dynamoDbClient;
        this.tableName = tableName;
    }

    public void writeCompleted(Transaction txn) {
        write(txn, "COMPLETED");
    }

    public void writeFailed(Transaction txn) {
        write(txn, txn.getStatus().name());
    }

    public void writeReversed(Transaction txn) {
        write(txn, "REVERSED");
    }

    private void write(Transaction txn, String status) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(buildItem(txn, status))
                    .build());
            log.debug("Analytics DynamoDB write: txnId={} status={}",
                    txn.getTransactionId(), status);
        } catch (Exception e) {
            log.warn("Analytics DynamoDB write failed (non-fatal): txnId={} status={} error={}",
                    txn.getTransactionId(), status, e.getMessage());
        }
    }

    private Map<String, AttributeValue> buildItem(Transaction txn, String status) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put("PK", s("TXN#" + txn.getTransactionId()));
        item.put("SK", s("METADATA"));

        // Required by analytics aggregator's is_transaction_record() filter
        item.put("transactionId", s(txn.getTransactionId().toString()));
        item.put("userId", s(txn.getUserId().toString()));
        item.put("amount", n(txn.getAmount().toPlainString()));
        item.put("status", s(status));

        item.put("currency", s(txn.getCurrency() != null ? txn.getCurrency() : "MXN"));
        item.put("transactionType", s(txn.getTransactionType().name()));
        item.put("paymentNetwork", s(resolvePaymentNetwork(txn)));
        item.put("sourceAccountId", s(txn.getSourceAccountId().toString()));

        if (txn.getTargetAccountId() != null)
            item.put("targetAccountId", s(txn.getTargetAccountId().toString()));
        if (txn.getMerchantName() != null)
            item.put("merchantName", s(txn.getMerchantName()));
        if (txn.getMerchantCategoryCode() != null) {
            item.put("merchantCategoryCode", s(txn.getMerchantCategoryCode()));
            // `category` is used as the SK suffix in analytics category stats.
            // Using MCC code as the key; aggregator resolves the display name from it.
            item.put("category", s(txn.getMerchantCategoryCode()));
        }
        if (txn.getFraudScore() != null)
            item.put("fraudScore", n(txn.getFraudScore().toPlainString()));
        if (txn.getFraudDecision() != null)
            item.put("fraudDecision", s(txn.getFraudDecision()));
        if (txn.getCompletedAt() != null)
            item.put("completedAt", s(txn.getCompletedAt().toString()));
        if (txn.getCreatedAt() != null)
            item.put("createdAt", s(txn.getCreatedAt().toString()));

        return item;
    }

    private String resolvePaymentNetwork(Transaction txn) {
        if (txn.getChannel() == null) return "INTERNAL";
        return switch (txn.getChannel()) {
            case ATPM -> "ATM";
            case BRANCH -> "BRANCH";
            default -> "INTERNAL";
        };
    }

    private static AttributeValue s(String v) { return AttributeValue.fromS(v); }
    private static AttributeValue n(String v) { return AttributeValue.fromN(v); }
}
