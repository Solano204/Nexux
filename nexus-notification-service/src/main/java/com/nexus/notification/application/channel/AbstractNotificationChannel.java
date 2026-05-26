package com.nexus.notification.application.channel;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.NotificationDocument.ChannelDeliveryStatus;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationChannel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * Abstract Notification Channel — Template Method Pattern.
 *
 * Defines the fixed algorithm skeleton:
 * 1. validateContent()     → channel-specific validation
 * 2. formatForChannel()    → channel-specific formatting
 * 3. deliver()             → actual delivery (SUBCLASS IMPLEMENTS)
 * 4. recordAttempt()       → common logging + metrics
 * 5. handleFailure()       → common error handling
 *
 * Subclasses override:
 * - getChannelType()
 * - validateContent()
 * - formatForChannel()
 * - deliver()
 *
 * Pattern: Template Method — algorithm structure fixed in base class
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractNotificationChannel {

    protected final ObservationRegistry observationRegistry;

    /**
     * THE TEMPLATE METHOD — final, cannot be overridden.
     * Fixed algorithm that all channels follow.
     */
    public final ChannelDeliveryStatus send(
            NotificationContent content,
            UserNotificationPreferences prefs,
            String userId) {

        Observation obs = Observation.createNotStarted(
                "notification.channel." + getChannelType().name().toLowerCase(),
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Step 1: Validate content for this channel
            ValidationResult validation = validateContent(content);
            if (!validation.isValid()) {
                log.warn("Content validation failed for channel {}: {}",
                        getChannelType(), validation.reason());
                obs.event(Observation.Event.of("validation.failed"));
                return ChannelDeliveryStatus.builder()
                        .status("SKIPPED")
                        .reason("VALIDATION_FAILED: " + validation.reason())
                        .attempts(0)
                        .build();
            }

            // Step 2: Format for this channel
            Object formatted = formatForChannel(content, prefs, userId);

            // Step 3: Deliver (subclass implements)
            DeliveryResult result = deliver(formatted, prefs, userId);

            // Step 4: Record attempt
            recordAttempt(result, content, userId);

            obs.lowCardinalityKeyValue("status", result.status());

            return ChannelDeliveryStatus.builder()
                    .status(result.status())
                    .deliveredAt(result.succeeded()
                            ? Instant.now() : null)
                    .providerMessageId(result.providerMessageId())
                    .reason(result.failureReason())
                    .attempts(1)
                    .build();

        } catch (Exception e) {
            obs.error(e);
            log.error("Channel {} delivery failed for userId={}: {}",
                    getChannelType(), userId, e.getMessage(), e);

            return ChannelDeliveryStatus.builder()
                    .status("FAILED")
                    .reason("EXCEPTION: " + e.getMessage())
                    .attempts(1)
                    .build();
        } finally {
            obs.stop();
        }
    }

    // ── Abstract methods — subclasses MUST implement ──────

    public abstract NotificationChannel getChannelType();

    protected abstract ValidationResult validateContent(
            NotificationContent content);

    protected abstract Object formatForChannel(
            NotificationContent content,
            UserNotificationPreferences prefs,
            String userId);

    protected abstract DeliveryResult deliver(
            Object formatted,
            UserNotificationPreferences prefs,
            String userId);

    // ── Common implementation — subclasses MAY override ───

    protected void recordAttempt(DeliveryResult result,
                                 NotificationContent content,
                                 String userId) {
        if (result.succeeded()) {
            log.info("Notification delivered: channel={} userId={}",
                    getChannelType(), userId);
        } else {
            log.warn("Notification delivery failed: channel={} " +
                            "userId={} reason={}",
                    getChannelType(), userId, result.failureReason());
        }
    }

    // ── Inner types ───────────────────────────────────────

    public record ValidationResult(boolean isValid, String reason) {
        static ValidationResult valid() {
            return new ValidationResult(true, null);
        }
        static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    public record DeliveryResult(
            boolean succeeded,
            String status,
            String providerMessageId,
            String failureReason
    ) {
        static DeliveryResult success(String messageId) {
            return new DeliveryResult(true, "DELIVERED", messageId, null);
        }
        static DeliveryResult failure(String reason) {
            return new DeliveryResult(false, "FAILED", null, reason);
        }
        static DeliveryResult skipped(String reason) {
            return new DeliveryResult(false, "SKIPPED", null, reason);
        }
    }
}