package com.nexus.notification.application.channel;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationChannel;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Push Notification Channel — AWS SNS-based push delivery.
 * Validates: title ≤ 65 chars, body ≤ 255 chars.
 * Formats: creates SNS APNS + GCM JSON payload.
 * Delivers: publishes to each device ARN.
 */
@Slf4j
@Component
public class PushNotificationChannel
        extends AbstractNotificationChannel {

    public PushNotificationChannel(
            ObservationRegistry observationRegistry) {
        super(observationRegistry);
    }

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.PUSH;
    }

    @Override
    protected ValidationResult validateContent(
            NotificationContent content) {
        if (content.title() != null &&
                content.title().length() > 65) {
            return ValidationResult.invalid(
                    "Title exceeds 65 char push limit");
        }
        if (content.shortBody() != null &&
                content.shortBody().length() > 255) {
            return ValidationResult.invalid(
                    "Body exceeds 255 char push limit");
        }
        return ValidationResult.valid();
    }

    @Override
    protected Object formatForChannel(
            NotificationContent content,
            UserNotificationPreferences prefs,
            String userId) {

        // Build SNS push payload for both APNS and FCM
        return Map.of(
                "default", content.title() + ": " + content.shortBody(),
                "APNS", buildApnsPayload(content),
                "APNS_SANDBOX", buildApnsPayload(content),
                "GCM", buildFcmPayload(content)
        );
    }

    @Override
    protected DeliveryResult deliver(Object formatted,
                                     UserNotificationPreferences prefs,
                                     String userId) {

        List<String> deviceArns =
                prefs.getPushConfig() != null
                        ? prefs.getPushConfig().deviceArns()
                        : List.of();

        if (deviceArns == null || deviceArns.isEmpty()) {
            return DeliveryResult.skipped(
                    "NO_DEVICE_REGISTERED");
        }

        // Simulate SNS delivery in local environment
        // In production: call AWS SNS for each device ARN
        for (String arn : deviceArns) {
            log.info("PUSH (simulated): arn={} userId={} title={}",
                    arn, userId,
                    ((Map<?, ?>) formatted).get("default"));
        }

        return DeliveryResult.success(
                "simulated-sns-" + System.currentTimeMillis());
    }

    private String buildApnsPayload(NotificationContent content) {
        return """
            {"aps":{"alert":{"title":"%s","body":"%s"},
             "badge":1,"sound":"default"},
             "deepLink":"%s"}""".formatted(
                escape(content.title()),
                escape(content.shortBody()),
                content.deepLinkPath() != null
                        ? content.deepLinkPath() : "/home");
    }

    private String buildFcmPayload(NotificationContent content) {
        return """
            {"notification":{"title":"%s","body":"%s"},
             "data":{"deepLink":"%s"}}""".formatted(
                escape(content.title()),
                escape(content.shortBody()),
                content.deepLinkPath() != null
                        ? content.deepLinkPath() : "/home");
    }

    private String escape(String s) {
        return s != null ? s.replace("\"", "\\\"") : "";
    }
}