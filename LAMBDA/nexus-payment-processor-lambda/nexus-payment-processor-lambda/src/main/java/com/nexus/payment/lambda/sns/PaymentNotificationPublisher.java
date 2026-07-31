package com.nexus.payment.lambda.sns;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.payment.lambda.model.NormalizedPaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.Map;

/**
 * Payment Notification Publisher — SNS fan-out.
 *
 * After successful Kafka bridge publish, also publishes to SNS
 * payment.processed topic. SNS fans out to:
 * - nexus-notification-dispatcher-lambda (push notification)
 * - nexus-analytics-aggregator-lambda (spending analytics)
 *
 * SNS publish is BEST-EFFORT — failure does not fail the message.
 * Kafka is the durable event bus; SNS is for real-time fan-out.
 *
 * SNS message attributes allow subscribers to filter by:
 * - network (VISA, MASTERCARD, etc.)
 * - eventType (AUTHORIZATION, CAPTURE, etc.)
 * - isApproved (true/false)
 */
public class PaymentNotificationPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentNotificationPublisher.class);

    private final SnsClient sns;
    private final String topicArn;
    private final ObjectMapper mapper;

    public PaymentNotificationPublisher(SnsClient sns,
                                        String topicArn,
                                        ObjectMapper mapper) {
        this.sns = sns;
        this.topicArn = topicArn;
        this.mapper = mapper;
    }

    /**
     * Publishes to SNS. Returns messageId on success, null on failure.
     */
    public String publish(NormalizedPaymentEvent event) {
        try {
            String message = mapper.writeValueAsString(event);

            PublishResponse response = sns.publish(
                    PublishRequest.builder()
                            .topicArn(topicArn)
                            .message(message)
                            .subject("payment." +
                                    event.eventType().toLowerCase())
                            // Message attributes for subscriber filter policies
                            .messageAttributes(Map.of(
                                    "network",
                                    snsStringAttr(event.network()),
                                    "eventType",
                                    snsStringAttr(event.eventType()),
                                    "isApproved",
                                    snsStringAttr(String.valueOf(
                                            event.isApproved())),
                                    "currency",
                                    snsStringAttr(event.currency()),
                                    "userId",
                                    snsStringAttr(event.userId())))
                            .build());

            log.debug("SNS published: messageId={} eventId={}",
                    response.messageId(), event.eventId());

            return response.messageId();

        } catch (Exception e) {
            log.warn("SNS publish failed (non-fatal): eventId={} {}",
                    event.eventId(), e.getMessage());
            return null;
        }
    }

    private software.amazon.awssdk.services.sns.model
            .MessageAttributeValue snsStringAttr(String value) {
        return software.amazon.awssdk.services.sns.model
                .MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value != null ? value : "UNKNOWN")
                .build();
    }
}