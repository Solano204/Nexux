package com.nexus.notification.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * DeliveryResult — outcome of a single delivery attempt.
 */
public record DeliveryResult(
        String channel,
        String outcome,              // DELIVERED, FAILED
        String providerMessageId,    // SES messageId or SNS messageId
        long durationMs,
        String failureReason
) {
    public static DeliveryResult success(String channel,
                                         String messageId,
                                         long durationMs) {
        return new DeliveryResult(channel, "DELIVERED",
                messageId, durationMs, null);
    }

    public static DeliveryResult failure(String channel,
                                         String reason,
                                         long durationMs) {
        return new DeliveryResult(channel, "FAILED",
                null, durationMs, reason);
    }
}
