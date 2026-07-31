package com.nexus.notification.lambda.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.notification.lambda.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.util.*;

/**
 * Push Dispatcher — delivers via AWS SNS platform endpoints.
 *
 * SNS routes to APNs (iOS) or FCM (Android) based on endpoint ARN type:
 *   arn:aws:sns:{region}:{account}:endpoint/APNS/{appName}/{uuid}
 *   arn:aws:sns:{region}:{account}:endpoint/GCM/{appName}/{uuid}
 *
 * Payload structure: SNS MessageStructure=json requires a JSON object
 * with keys matching the platform:
 *   "APNS"         → iOS production
 *   "APNS_SANDBOX" → iOS development/TestFlight
 *   "GCM"          → Android (FCM via SNS GCM key)
 *   "default"      → plain text fallback
 *
 * EndpointDisabledException:
 * User uninstalled the app. SNS marks the endpoint as disabled.
 * We catch this specifically and report isEndpointInvalid=true
 * so the Plane A notification service can clean up the stored ARN.
 * Future notifications skip this device immediately.
 *
 * iOS interruption-level (iOS 15+):
 * HIGH/CRITICAL priority → "time-sensitive"
 * Bypasses Focus mode. Appropriate for fraud alerts.
 * NORMAL → "active" (standard notification)
 */
public class PushDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(PushDispatcher.class);

    private static final int APNS_TITLE_LIMIT = 65;
    private static final int APNS_BODY_LIMIT = 255;

    private final SnsClient sns;
    private final ObjectMapper mapper;

    public PushDispatcher(SnsClient sns, ObjectMapper mapper) {
        this.sns = sns;
        this.mapper = mapper;
    }

    public DeliveryResult dispatch(DispatchRequest request) {
        long startMs = System.currentTimeMillis();

        try {
            String endpointArn = request.deviceEndpointArn();
            if (endpointArn == null || endpointArn.isBlank()) {
                throw new DeliveryException("PUSH",
                        "deviceEndpointArn is required", false);
            }

            // ── Build platform-specific payload ───────────────
            String payload = buildPayload(request);

            // ── Publish to device endpoint ─────────────────────
            PublishRequest publishRequest = PublishRequest.builder()
                    .targetArn(endpointArn)
                    .messageStructure("json")
                    .message(payload)
                    // Subject used as APNs alert title (fallback)
                    .subject(truncate(request.pushTitle(),
                            APNS_TITLE_LIMIT))
                    .messageAttributes(Map.of(
                            "notificationId",
                            MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(request.notificationId())
                                    .build()))
                    .build();

            PublishResponse response =
                    sns.publish(publishRequest);

            long durationMs = System.currentTimeMillis() - startMs;

            log.info("Push delivered: notificationId={} " +
                            "snsMessageId={} platform={} durationMs={}",
                    request.notificationId(),
                    response.messageId(),
                    request.platform(),
                    durationMs);

            return DeliveryResult.success("PUSH",
                    response.messageId(), durationMs);

        } catch (EndpointDisabledException e) {
            // User uninstalled app — permanent failure, clean up ARN
            DeliveryException ex = new DeliveryException("PUSH",
                    "Push endpoint disabled: " + request.deviceEndpointArn(),
                    false);
            ex.setEndpointInvalid(true);
            ex.setEndpointArn(request.deviceEndpointArn());
            throw ex;

        } catch (InvalidParameterException e) {
            // Malformed ARN
            throw new DeliveryException("PUSH",
                    "Invalid endpoint ARN: " + e.getMessage(), false);

        } catch (SnsException e) {
            boolean transient_ = e.statusCode() >= 500;
            throw new DeliveryException("PUSH",
                    "SNS push error " + e.statusCode() + ": " +
                            e.getMessage(), transient_);
        }
    }

    /**
     * Build the multi-platform JSON payload for SNS MessageStructure=json.
     */
    private String buildPayload(DispatchRequest request)
            throws DeliveryException {

        boolean highPriority = "HIGH".equals(request.priority())
                || "CRITICAL".equals(request.priority());

        // ── iOS APNs payload ──────────────────────────────────
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", Map.of(
                "title", truncate(request.pushTitle(), APNS_TITLE_LIMIT),
                "body",  truncate(request.pushBody(), APNS_BODY_LIMIT)));
        aps.put("sound", "default");
        aps.put("badge", 1);
        // iOS 15+ Focus mode bypass for financial alerts
        aps.put("interruption-level",
                highPriority ? "time-sensitive" : "active");

        Map<String, Object> apnsPayload = new LinkedHashMap<>();
        apnsPayload.put("aps", aps);
        apnsPayload.put("notificationId", request.notificationId());
        apnsPayload.put("deepLink", request.deepLinkPath());
        apnsPayload.put("eventType", request.eventType());
        if (request.pushData() != null) {
            apnsPayload.putAll(request.pushData());
        }

        // ── Android FCM payload ───────────────────────────────
        Map<String, Object> fcmNotification = Map.of(
                "title", truncate(request.pushTitle(), APNS_TITLE_LIMIT),
                "body",  truncate(request.pushBody(), APNS_BODY_LIMIT),
                "sound", "default",
                "click_action", "OPEN_DEEP_LINK");

        Map<String, Object> fcmData = new LinkedHashMap<>();
        fcmData.put("notificationId", request.notificationId());
        fcmData.put("deepLink", request.deepLinkPath());
        fcmData.put("eventType", request.eventType());
        if (request.pushData() != null) {
            fcmData.putAll(request.pushData());
        }

        Map<String, Object> gcmMessage = Map.of(
                "notification", fcmNotification,
                "data", fcmData,
                "priority", highPriority ? "high" : "normal",
                "ttl", "86400s");

        // ── Assemble SNS multi-platform envelope ──────────────
        try {
            Map<String, String> envelope = new LinkedHashMap<>();
            envelope.put("default",
                    request.pushTitle() + ": " + request.pushBody());
            envelope.put("APNS",
                    mapper.writeValueAsString(apnsPayload));
            envelope.put("APNS_SANDBOX",
                    mapper.writeValueAsString(apnsPayload));
            envelope.put("GCM",
                    mapper.writeValueAsString(
                            Map.of("message", gcmMessage)));

            return mapper.writeValueAsString(envelope);

        } catch (JsonProcessingException e) {
            throw new DeliveryException("PUSH",
                    "Failed to serialize push payload: " + e.getMessage(),
                    false);
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text :
                text.substring(0, max - 1) + "…";
    }
}