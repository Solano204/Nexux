package com.nexus.notification.application.channel;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationChannel;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Email Notification Channel — AWS SES delivery.
 * Wraps body in HTML email template.
 * Validates: title ≤ 100 chars.
 */
@Slf4j
@Component
public class EmailNotificationChannel
        extends AbstractNotificationChannel {

    public EmailNotificationChannel(
            ObservationRegistry observationRegistry) {
        super(observationRegistry);
    }

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.EMAIL;
    }

    @Override
    protected ValidationResult validateContent(
            NotificationContent content) {
        if (content.title() != null &&
                content.title().length() > 100) {
            return ValidationResult.invalid(
                    "Email subject exceeds 100 chars");
        }
        return ValidationResult.valid();
    }

    @Override
    protected Object formatForChannel(
            NotificationContent content,
            UserNotificationPreferences prefs,
            String userId) {

        // In production: use Thymeleaf to render HTML email
        return buildSimpleEmailHtml(content);
    }

    @Override
    protected DeliveryResult deliver(Object formatted,
                                     UserNotificationPreferences prefs,
                                     String userId) {

        String emailAddress = prefs.getEmailConfig() != null
                ? prefs.getEmailConfig().address() : null;

        if (emailAddress == null || emailAddress.isBlank()) {
            return DeliveryResult.skipped("NO_EMAIL_ADDRESS");
        }

        // Simulate SES delivery
        log.info("EMAIL (simulated): to={} subject={}",
                emailAddress, "Email notification");

        return DeliveryResult.success(
                "simulated-ses-" + System.currentTimeMillis());
    }

    private String buildSimpleEmailHtml(NotificationContent content) {
        return """
            <html><body>
            <h2>%s</h2>
            <p>%s</p>
            <a href="%s">%s</a>
            </body></html>
            """.formatted(
                content.title(),
                content.body(),
                "https://app.nexus.com" + (content.deepLinkPath() != null
                        ? content.deepLinkPath() : "/home"),
                content.callToAction() != null
                        ? content.callToAction() : "Ver en Nexus");
    }
}