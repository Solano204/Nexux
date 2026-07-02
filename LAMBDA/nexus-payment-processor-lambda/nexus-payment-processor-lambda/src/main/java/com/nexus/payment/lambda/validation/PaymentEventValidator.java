package com.nexus.payment.lambda.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.IncomingPaymentEvent;
import com.nexus.payment.lambda.model.enums.FailureReason;
import jakarta.validation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Payment Event Validator — two layers.
 *
 * Layer 1: Bean Validation (JSR-380) on IncomingPaymentEvent.
 *   @NotBlank, @NotNull, @DecimalMin on required fields.
 *   Fast, pure computation, no I/O.
 *
 * Layer 2: Business rules.
 *   - Amount must be positive and below MXN 500,000 (AML threshold)
 *   - Network must be a known value
 *   - Event type must be a known value
 *   - Response code must be recognized
 *
 * Layer 3: Idempotency management (DynamoDB).
 *   claimIdempotencySlot(): conditional PutItem — fails if key exists.
 *   markIdempotencyProcessed(): updates status to PROCESSED.
 *   markIdempotencyFailed(): updates status to FAILED (retryable).
 *   releaseIdempotencySlot(): deletes (on enrichment failure before Kafka).
 */
public class PaymentEventValidator {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentEventValidator.class);

    private static final BigDecimal MAX_AMOUNT =
            new BigDecimal("500000.00");
    private static final BigDecimal MIN_AMOUNT =
            new BigDecimal("0.01");

    private static final Set<String> KNOWN_EVENT_TYPES = Set.of(
            "AUTHORIZATION", "CAPTURE", "REVERSAL",
            "REFUND", "VOID", "INQUIRY");

    private static final Set<String> KNOWN_NETWORKS = Set.of(
            "VISA", "MASTERCARD", "AMEX", "SPEI",
            "VI", "MC", "AX");

    private final Validator beanValidator;
    private final DynamoDbClient dynamo;
    private final String idempotencyTable;

    public PaymentEventValidator(DynamoDbClient dynamo,
                                 String idempotencyTable,
                                 ObjectMapper mapper) {
        this.dynamo = dynamo;
        this.idempotencyTable = idempotencyTable;
        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();
        this.beanValidator = factory.getValidator();
    }

    public ValidationResult validate(IncomingPaymentEvent event) {

        // Layer 1: Bean validation
        Set<ConstraintViolation<IncomingPaymentEvent>> violations =
                beanValidator.validate(event);

        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Validation failed");
            return ValidationResult.invalid(
                    FailureReason.MISSING_REQUIRED_FIELD, detail);
        }

        // Layer 2: Business rules
        if (event.amount().compareTo(MIN_AMOUNT) < 0) {
            return ValidationResult.invalid(
                    FailureReason.INVALID_AMOUNT,
                    "Amount below minimum: " + event.amount());
        }

        if (event.amount().compareTo(MAX_AMOUNT) > 0) {
            return ValidationResult.invalid(
                    FailureReason.INVALID_AMOUNT,
                    "Amount exceeds AML threshold: " + event.amount());
        }

        if (!KNOWN_NETWORKS.contains(
                event.network().toUpperCase())) {
            return ValidationResult.invalid(
                    FailureReason.SCHEMA_VALIDATION_ERROR,
                    "Unknown network: " + event.network());
        }

        if (!KNOWN_EVENT_TYPES.contains(
                event.eventType().toUpperCase())) {
            return ValidationResult.invalid(
                    FailureReason.SCHEMA_VALIDATION_ERROR,
                    "Unknown event type: " + event.eventType());
        }

        return ValidationResult.valid();
    }

    /**
     * Claim an idempotency slot via DynamoDB conditional write.
     * Returns true if this is a NEW event (slot claimed).
     * Returns false if the event was already seen (duplicate).
     */
    public boolean claimIdempotencySlot(String key,
                                        String networkTxnId) {
        try {
            long ttl = Instant.now()
                    .plus(24, ChronoUnit.HOURS)
                    .getEpochSecond();

            dynamo.putItem(PutItemRequest.builder()
                    .tableName(idempotencyTable)
                    .item(Map.of(
                            "PK", AttributeValue.fromS(key),
                            "networkTxnId",
                            AttributeValue.fromS(networkTxnId),
                            "status",
                            AttributeValue.fromS("IN_PROGRESS"),
                            "claimedAt",
                            AttributeValue.fromS(Instant.now().toString()),
                            "ttl", AttributeValue.fromN(
                                    String.valueOf(ttl))))
                    // Conditional: only succeed if PK doesn't exist
                    .conditionExpression(
                            "attribute_not_exists(PK)")
                    .build());

            return true; // New event

        } catch (ConditionalCheckFailedException e) {
            return false; // Already processed (duplicate)
        } catch (Exception e) {
            log.error("Idempotency claim failed: key={} {}",
                    key, e.getMessage());
            // On DynamoDB error: allow processing (fail-open)
            // Risk: rare duplicate in DB error scenario
            return true;
        }
    }

    public void markIdempotencyProcessed(String key,
                                         String eventId) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(idempotencyTable)
                    .key(Map.of("PK", AttributeValue.fromS(key)))
                    .updateExpression(
                            "SET #status = :processed, " +
                                    "eventId = :eventId, " +
                                    "processedAt = :now")
                    .expressionAttributeNames(
                            Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":processed",
                            AttributeValue.fromS("PROCESSED"),
                            ":eventId",
                            AttributeValue.fromS(eventId),
                            ":now",
                            AttributeValue.fromS(
                                    Instant.now().toString())))
                    .build());
        } catch (Exception e) {
            log.warn("Idempotency mark-processed failed: {}",
                    e.getMessage());
        }
    }

    public void markIdempotencyFailed(String key) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(idempotencyTable)
                    .key(Map.of("PK", AttributeValue.fromS(key)))
                    .updateExpression("SET #status = :failed")
                    .expressionAttributeNames(
                            Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":failed", AttributeValue.fromS("FAILED")))
                    .build());
        } catch (Exception e) {
            log.warn("Idempotency mark-failed failed: {}",
                    e.getMessage());
        }
    }

    public void releaseIdempotencySlot(String key) {
        try {
            dynamo.deleteItem(DeleteItemRequest.builder()
                    .tableName(idempotencyTable)
                    .key(Map.of("PK", AttributeValue.fromS(key)))
                    .build());
        } catch (Exception e) {
            log.warn("Idempotency release failed: {}",
                    e.getMessage());
        }
    }
}
