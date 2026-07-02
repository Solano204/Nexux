package com.nexus.notification.lambda.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.notification.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.Map;

/**
 * Delivery Status Reporter — publishes outcomes to SQS.
 *
 * The nexus-notification-service on Plane A consumes this queue
 * and updates MongoDB notification delivery status:
 *   DELIVERED → mark channel as delivered, increment Redis unread
 *   FAILED    → trigger fallback channel (e.g., email → in-app)
 *   ENDPOINT_INVALID → remove device ARN from user preferences
 *
 * Status reporting is BEST-EFFORT: if SQS publish fails, the
 * notification was already delivered. We log the error but
 * do not fail the Lambda invocation.
 */
public class DeliveryStatusReporter {

    private static final Logger log =
            LoggerFactory.getLogger(DeliveryStatusReporter.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper;

    public DeliveryStatusReporter(SqsClient sqs, String queueUrl,
                                  ObjectMapper mapper) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.mapper = mapper;
    }

    public void reportSuccess(DispatchRequest request,
                              DeliveryResult result) {
        DeliveryStatusEvent event = new DeliveryStatusEvent(
                request.notificationId(),
                request.dispatchId(),
                request.userId(),
                request.channel(),
                "DELIVERED",
                result.providerMessageId(),
                null, false, null,
                Instant.now(),
                result.durationMs());
        publish(event);
    }

    public void reportFailure(DispatchRequest request,
                              DeliveryException ex,
                              long durationMs) {
        DeliveryStatusEvent event = new DeliveryStatusEvent(
                request.notificationId(),
                request.dispatchId(),
                request.userId(),
                request.channel(),
                "PERMANENTLY_FAILED",
                null,
                ex.getMessage(),
                ex.isEndpointInvalid(),
                ex.getEndpointArn(),
                Instant.now(),
                durationMs);
        publish(event);
    }

    public void reportParseError(String dispatchId,
                                 String errorMessage) {
        DeliveryStatusEvent event = new DeliveryStatusEvent(
                null, dispatchId, null,
                "UNKNOWN", "PARSE_ERROR",
                null, errorMessage,
                false, null,
                Instant.now(), 0);
        publish(event);
    }

    private void publish(DeliveryStatusEvent event) {
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(mapper.writeValueAsString(event))
                    .messageAttributes(Map.of(
                            "channel",
                            sqsAttr(event.channel() != null
                                    ? event.channel() : "UNKNOWN"),
                            "outcome",
                            sqsAttr(event.outcome()),
                            "userId",
                            sqsAttr(event.userId() != null
                                    ? event.userId() : "unknown")))
                    .build());

            log.debug("Delivery status reported: notificationId={} " +
                    "outcome={}", event.notificationId(), event.outcome());

        } catch (Exception e) {
            // Non-fatal — notification was already delivered
            log.warn("Failed to publish delivery status " +
                            "(non-fatal): notificationId={} error={}",
                    event.notificationId(), e.getMessage());
        }
    }

    private software.amazon.awssdk.services.sqs.model
            .MessageAttributeValue sqsAttr(String value) {
        return software.amazon.awssdk.services.sqs.model
                .MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }
}