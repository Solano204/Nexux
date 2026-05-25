package com.nexus.payment.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.payment.lambda.bridge.KafkaBridgeClient;
import com.nexus.payment.lambda.dlq.DeadLetterHandler;
import com.nexus.payment.lambda.enrichment.PaymentEventEnricher;
import com.nexus.payment.lambda.model.*;
import com.nexus.payment.lambda.model.enums.FailureReason;
import com.nexus.payment.lambda.model.enums.PaymentStatus;
import com.nexus.payment.lambda.sns.PaymentNotificationPublisher;
import com.nexus.payment.lambda.validation.PaymentEventValidator;
import com.nexus.payment.lambda.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Payment Processor Handler — SQS batch trigger.
 *
 * SQS batch configuration (template.yaml):
 *   BatchSize: 10  — up to 10 messages per invocation
 *   MaximumBatchingWindowInSeconds: 5
 *   FunctionResponseTypes: ReportBatchItemFailures
 *
 * Partial batch failure: each message is processed independently.
 * If message N fails, only message N is reported as failed
 * (returned to SQS for retry). Messages 1-9 and 11+ are deleted.
 * This prevents a single bad message from blocking the whole batch.
 *
 * Processing pipeline per message:
 * 1. Deserialize raw JSON from SQS message body
 * 2. Validate: schema, required fields, amount range
 * 3. Idempotency: DynamoDB conditional write — skip duplicates
 * 4. Enrich: resolve nexusAccountId, normalize currency to MXN
 * 5. Bridge: publish to Plane A Kafka via MSK or HTTP endpoint
 * 6. Notify: publish to SNS payment.processed for fan-out
 * 7. Mark idempotency record as PROCESSED
 *
 * All static fields initialized at class load (Lambda warm start).
 * No SnapStart needed here: SQS Lambda is batch-oriented, cold
 * starts are less latency-sensitive than synchronous API calls.
 */
public class PaymentProcessorHandler
        implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentProcessorHandler.class);

    private static final int MAX_RETRYABLE_FAILURES_PER_BATCH = 3;

    // ── Static initialization — runs once per JVM ─────────
    private static final ObjectMapper MAPPER;
    private static final PaymentEventValidator VALIDATOR;
    private static final PaymentEventEnricher ENRICHER;
    private static final KafkaBridgeClient KAFKA_BRIDGE;
    private static final PaymentNotificationPublisher SNS_PUBLISHER;
    private static final DeadLetterHandler DLQ_HANDLER;

    static {
        MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature
                        .WRITE_DATES_AS_TIMESTAMPS);

        String region = System.getenv("AWS_REGION_NAME");
        String idempotencyTable = System.getenv(
                "DYNAMODB_IDEMPOTENCY_TABLE");
        String snsTopic = System.getenv(
                "SNS_PAYMENT_PROCESSED_ARN");
        String bridgeMode = System.getenv("KAFKA_BRIDGE_MODE");

        DynamoDbClient dynamo = DynamoDbClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        SnsClient sns = SnsClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        VALIDATOR = new PaymentEventValidator(dynamo, idempotencyTable,
                MAPPER);
        ENRICHER = new PaymentEventEnricher(MAPPER);
        KAFKA_BRIDGE = KafkaBridgeClient.create(bridgeMode, MAPPER);
        SNS_PUBLISHER = new PaymentNotificationPublisher(sns, snsTopic,
                MAPPER);
        DLQ_HANDLER = new DeadLetterHandler(MAPPER);

        log.info("Payment processor initialized: mode={}", bridgeMode);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event,
                                          Context context) {

        List<SQSBatchResponse.BatchItemFailure> failures =
                new ArrayList<>();

        int total = event.getRecords().size();
        int succeeded = 0;
        int duplicates = 0;
        int failed = 0;

        log.info("Processing SQS batch: size={} requestId={}",
                total, context.getAwsRequestId());

        for (SQSEvent.SQSMessage message : event.getRecords()) {

            String messageId = message.getMessageId();
            var segment = AWSXRay.beginSubsegment(
                    "ProcessPayment." + messageId.substring(0, 8));

            try {
                PaymentProcessingResult result =
                        processMessage(message, context);

                switch (result.status()) {
                    case PROCESSED  -> succeeded++;
                    case DUPLICATE  -> duplicates++;
                    case VALIDATION_FAILED -> {
                        // Validation failures are NOT retried —
                        // the message is structurally invalid.
                        // Log for investigation but do NOT add to
                        // failures list (prevents infinite retry).
                        log.warn("Validation failed: messageId={} " +
                                        "reason={}", messageId,
                                result.failureReason());
                        DLQ_HANDLER.recordValidationFailure(
                                message, result);
                        failed++;
                    }
                    case BRIDGE_FAILED -> {
                        // Kafka bridge failure IS retryable.
                        // Add to failures → SQS returns message
                        // to queue for retry.
                        log.error("Bridge failed: messageId={} " +
                                        "reason={}", messageId,
                                result.failureDetail());
                        failures.add(
                                SQSBatchResponse.BatchItemFailure
                                        .builder()
                                        .withItemIdentifier(messageId)
                                        .build());
                        failed++;
                    }
                    default -> {
                        log.error("Unexpected status: {} messageId={}",
                                result.status(), messageId);
                        failures.add(
                                SQSBatchResponse.BatchItemFailure
                                        .builder()
                                        .withItemIdentifier(messageId)
                                        .build());
                        failed++;
                    }
                }

                segment.putMetadata("status",
                        result.status().name());
                segment.putMetadata("networkTxnId",
                        result.networkTransactionId());

            } catch (Exception e) {
                // Unhandled exception — retryable
                log.error("Unhandled exception for messageId={}: {}",
                        messageId, e.getMessage(), e);
                segment.addException(e);
                failures.add(SQSBatchResponse.BatchItemFailure
                        .builder()
                        .withItemIdentifier(messageId)
                        .build());
                failed++;
            } finally {
                AWSXRay.endSubsegment();
            }
        }

        log.info("Batch complete: total={} succeeded={} " +
                        "duplicates={} failed={} failures={}",
                total, succeeded, duplicates, failed, failures.size());

        // Return failures — SQS will re-enqueue only these messages.
        // After maxReceiveCount (3) retries, they go to DLQ.
        return SQSBatchResponse.builder()
                .withBatchItemFailures(failures)
                .build();
    }

    /**
     * Full processing pipeline for a single SQS message.
     * Returns a PaymentProcessingResult — never throws.
     */
    private PaymentProcessingResult processMessage(
            SQSEvent.SQSMessage message, Context context) {

        long startMs = System.currentTimeMillis();
        String messageId = message.getMessageId();

        // ── Step 1: Deserialize ────────────────────────────────
        IncomingPaymentEvent incomingEvent;
        try {
            incomingEvent = MAPPER.readValue(
                    message.getBody(), IncomingPaymentEvent.class);
        } catch (Exception e) {
            log.warn("Deserialization failed: messageId={} {}",
                    messageId, e.getMessage());
            return failResult(messageId, null,
                    PaymentStatus.VALIDATION_FAILED,
                    FailureReason.SCHEMA_VALIDATION_ERROR,
                    e.getMessage(),
                    System.currentTimeMillis() - startMs);
        }

        String networkTxnId = incomingEvent.networkTransactionId();
        log.info("Processing payment: networkTxnId={} network={} " +
                        "eventType={} amount={} {}",
                networkTxnId,
                incomingEvent.network(),
                incomingEvent.eventType(),
                incomingEvent.amount(),
                incomingEvent.currency());

        // ── Step 2: Validate ───────────────────────────────────
        ValidationResult validation = VALIDATOR.validate(incomingEvent);

        if (!validation.isValid()) {
            return failResult(networkTxnId, null,
                    PaymentStatus.VALIDATION_FAILED,
                    validation.failureReason(),
                    validation.failureDetail(),
                    System.currentTimeMillis() - startMs);
        }

        // ── Step 3: Idempotency check ──────────────────────────
        String idempotencyKey = buildIdempotencyKey(incomingEvent);
        boolean isNew = VALIDATOR.claimIdempotencySlot(idempotencyKey,
                networkTxnId);

        if (!isNew) {
            log.info("Duplicate event suppressed: networkTxnId={}",
                    networkTxnId);
            return new PaymentProcessingResult(
                    networkTxnId, null,
                    PaymentStatus.DUPLICATE,
                    null, null, null, null,
                    FailureReason.DUPLICATE_EVENT, null,
                    System.currentTimeMillis() - startMs);
        }

        // ── Step 4: Enrich ─────────────────────────────────────
        NormalizedPaymentEvent normalized;
        try {
            normalized = ENRICHER.enrich(incomingEvent,
                    context.getAwsRequestId());
        } catch (Exception e) {
            log.error("Enrichment failed: networkTxnId={} {}",
                    networkTxnId, e.getMessage());
            VALIDATOR.releaseIdempotencySlot(idempotencyKey);
            return failResult(networkTxnId, null,
                    PaymentStatus.BRIDGE_FAILED,
                    FailureReason.ENRICHMENT_FAILED,
                    e.getMessage(),
                    System.currentTimeMillis() - startMs);
        }

        // ── Step 5: Bridge to Plane A Kafka ───────────────────
        KafkaBridgeResult bridgeResult = KAFKA_BRIDGE.publish(
                normalized);

        if (!bridgeResult.success()) {
            // Mark idempotency slot as FAILED so the retry
            // can re-claim it
            VALIDATOR.markIdempotencyFailed(idempotencyKey);
            return failResult(networkTxnId, normalized.eventId(),
                    PaymentStatus.BRIDGE_FAILED,
                    bridgeResult.failureReason(),
                    bridgeResult.failureDetail(),
                    System.currentTimeMillis() - startMs);
        }

        // ── Step 6: SNS fan-out ────────────────────────────────
        String snsMessageId = SNS_PUBLISHER.publish(normalized);
        // SNS failure is non-fatal — Kafka publish succeeded.
        // Notification is best-effort; downstream consumers will
        // receive the event from Kafka.
        if (snsMessageId == null) {
            log.warn("SNS publish failed for networkTxnId={} " +
                    "— Kafka publish succeeded", networkTxnId);
        }

        // ── Step 7: Mark idempotency as PROCESSED ─────────────
        VALIDATOR.markIdempotencyProcessed(idempotencyKey,
                normalized.eventId());

        long duration = System.currentTimeMillis() - startMs;
        log.info("Payment processed: networkTxnId={} eventId={} " +
                        "topic={} duration={}ms",
                networkTxnId, normalized.eventId(),
                bridgeResult.topic(), duration);

        return new PaymentProcessingResult(
                networkTxnId,
                normalized.eventId(),
                PaymentStatus.PROCESSED,
                bridgeResult.topic(),
                String.valueOf(bridgeResult.partition()),
                String.valueOf(bridgeResult.offset()),
                snsMessageId,
                FailureReason.NONE,
                null,
                duration);
    }

    private String buildIdempotencyKey(IncomingPaymentEvent e) {
        // Idempotency key: network + networkTransactionId + eventType
        // Same authorization event from same network = same key
        return String.format("PAY#%s#%s#%s",
                e.network().toUpperCase(),
                e.networkTransactionId(),
                e.eventType().toUpperCase());
    }

    private PaymentProcessingResult failResult(
            String networkTxnId, String eventId,
            PaymentStatus status, FailureReason reason,
            String detail, long durationMs) {
        return new PaymentProcessingResult(
                networkTxnId, eventId, status,
                null, null, null, null,
                reason, detail, durationMs);
    }
}