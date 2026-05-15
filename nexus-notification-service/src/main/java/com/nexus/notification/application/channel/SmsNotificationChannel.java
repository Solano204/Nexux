package com.nexus.notification.application.channel;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationChannel;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SMS Notification Channel — Mock SMS provider (Twilio in production).
 * Strictly enforces 160-char limit using NotificationContent.smsBody().
 * Prepends "NEXUS: " prefix.
 */
@Slf4j
@Component
public class SmsNotificationChannel
        extends AbstractNotificationChannel {

    public SmsNotificationChannel(
            ObservationRegistry observationRegistry) {
        super(observationRegistry);
    }

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.SMS;
    }

    @Override
    protected ValidationResult validateContent(
            NotificationContent content) {
        // smsBody() handles truncation — always valid after
        return ValidationResult.valid();
    }

    @Override
    protected Object formatForChannel(
            NotificationContent content,
            UserNotificationPreferences prefs,
            String userId) {
        // smsBody() applies "NEXUS: " prefix + 160 char truncation
        return content.smsBody();
    }

    @Override
    protected DeliveryResult deliver(Object formatted,
                                     UserNotificationPreferences prefs,
                                     String userId) {

        String phoneNumber = prefs.getSmsConfig() != null
                ? prefs.getSmsConfig().phoneNumber() : null;

        if (phoneNumber == null || phoneNumber.isBlank()) {
            return DeliveryResult.skipped("NO_PHONE_NUMBER");
        }

        // Simulate SMS delivery
        log.info("SMS (simulated): to={} body={}",
                phoneNumber, formatted);

        return DeliveryResult.success(
                "simulated-sms-" + System.currentTimeMillis());
    }
}