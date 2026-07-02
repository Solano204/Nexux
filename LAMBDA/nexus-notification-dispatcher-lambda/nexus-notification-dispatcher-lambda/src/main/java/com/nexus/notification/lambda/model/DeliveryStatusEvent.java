package com.nexus.notification.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;


/**
 * DeliveryStatusEvent — published to SQS for Plane A to consume.
 * Plane A uses this to update MongoDB notification delivery status.
 */
public record DeliveryStatusEvent(
        String notificationId,
        String dispatchId,
        String userId,
        String channel,
        String outcome,
        String providerMessageId,
        String failureReason,
        boolean isEndpointInvalid,
        String invalidEndpointArn,
        Instant deliveredAt,
        long durationMs
) {}