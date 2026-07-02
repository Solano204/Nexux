package com.nexus.payment.lambda.dlq;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.PaymentProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dead Letter Handler — structured logging for DLQ-bound messages.
 *
 * Validation failures are NOT retried (structurally invalid).
 * We log them structurally so CloudWatch Insights can query:
 *   fields @timestamp, failureReason, networkTxnId
 *   | filter failureReason = "SCHEMA_VALIDATION_ERROR"
 *
 * SQS will route to the physical DLQ after maxReceiveCount
 * retries for retryable failures (BRIDGE_FAILED).
 */
public class DeadLetterHandler {

    private static final Logger log =
            LoggerFactory.getLogger(DeadLetterHandler.class);

    private final ObjectMapper mapper;

    public DeadLetterHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void recordValidationFailure(
            SQSEvent.SQSMessage message,
            PaymentProcessingResult result) {
        try {
            var dlqEntry = java.util.Map.of(
                    "dlqType", "VALIDATION_FAILURE",
                    "messageId", message.getMessageId(),
                    "networkTxnId", result.networkTransactionId() != null
                            ? result.networkTransactionId() : "UNKNOWN",
                    "failureReason", result.failureReason().name(),
                    "failureDetail", result.failureDetail() != null
                            ? result.failureDetail() : "",
                    "originalBody", message.getBody().length() > 500
                            ? message.getBody().substring(0, 500) + "..."
                            : message.getBody(),
                    "recordedAt",
                    java.time.Instant.now().toString()
            );

            // Structured log for CloudWatch Logs Insights
            log.error("DLQ_ENTRY: {}",
                    mapper.writeValueAsString(dlqEntry));

        } catch (Exception e) {
            log.error("DLQ handler error: {}", e.getMessage());
        }
    }
}