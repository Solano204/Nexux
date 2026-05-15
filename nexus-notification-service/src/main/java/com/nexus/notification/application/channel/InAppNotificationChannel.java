package com.nexus.notification.application.channel;

import com.nexus.notification.domain.model.*;
import com.nexus.notification.domain.model.enums.NotificationChannel;
import com.nexus.notification.infrastructure.mongodb.NotificationRepository;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * In-App Notification Channel — MongoDB write + Redis counter.
 * No size limits. Always delivered (MongoDB is local).
 * Increments unread badge counter in Redis.
 */
@Slf4j
@Component
public class InAppNotificationChannel
        extends AbstractNotificationChannel {

    private final NotificationRepository notificationRepository;
    private final NotificationRedisRepository redisRepository;

    public InAppNotificationChannel(
            ObservationRegistry observationRegistry,
            NotificationRepository notificationRepository,
            NotificationRedisRepository redisRepository) {
        super(observationRegistry);
        this.notificationRepository = notificationRepository;
        this.redisRepository = redisRepository;
    }

    @Override
    public NotificationChannel getChannelType() {
        return NotificationChannel.IN_APP;
    }

    @Override
    protected ValidationResult validateContent(
            NotificationContent content) {
        // No character limits for in-app
        return ValidationResult.valid();
    }

    @Override
    protected Object formatForChannel(
            NotificationContent content,
            UserNotificationPreferences prefs,
            String userId) {

        return Map.of(
                "title", content.title(),
                "body", content.body(),
                "callToAction", content.callToAction(),
                "deepLinkPath", content.deepLinkPath() != null
                        ? content.deepLinkPath() : "/home",
                "tone", content.tone().name(),
                "highlights", content.highlights(),
                "requiresAction", content.requiresAction(),
                "isRead", false,
                "createdAt", Instant.now().toString()
        );
    }

    @Override
    protected DeliveryResult deliver(Object formatted,
                                     UserNotificationPreferences prefs,
                                     String userId) {
        // Increment Redis unread counter
        redisRepository.incrementUnreadCount(userId);

        log.debug("In-app notification delivered: userId={}", userId);

        return DeliveryResult.success("in-app-" +
                System.currentTimeMillis());
    }
}