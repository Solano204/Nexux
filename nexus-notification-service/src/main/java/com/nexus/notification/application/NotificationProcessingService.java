package com.nexus.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.notification.application.ai.NotificationContentGenerator;
import com.nexus.notification.application.channel.*;
import com.nexus.notification.domain.model.*;
import com.nexus.notification.domain.model.enums.*;
import com.nexus.notification.infrastructure.mongodb.*;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Notification Processing Service — Core pipeline orchestrator.
 *
 * Processing steps:
 * 1. Deduplication check (Redis)
 * 2. Rate limit check (Redis)
 * 3. Concurrent data gathering (Virtual Threads)
 * 4. Quiet hours check
 * 5. AI content generation
 * 6. Channel selection
 * 7. Parallel channel delivery
 * 8. MongoDB persistence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProcessingService {

    private final NotificationContentGenerator contentGenerator;
    private final NotificationRepository notificationRepository;
    private final PreferencesRepository preferencesRepository;
    private final NotificationRedisRepository redisRepository;
    private final PushNotificationChannel pushChannel;
    private final EmailNotificationChannel emailChannel;
    private final SmsNotificationChannel smsChannel;
    private final InAppNotificationChannel inAppChannel;
    private final ObservationRegistry observationRegistry;

    private final Counter rateLimitedCounter;
    private final Counter dedupSkippedCounter;
    private final Counter quietHoursDeferredCounter;

    public NotificationProcessingService(
            NotificationContentGenerator contentGenerator,
            NotificationRepository notificationRepository,
            PreferencesRepository preferencesRepository,
            NotificationRedisRepository redisRepository,
            PushNotificationChannel pushChannel,
            EmailNotificationChannel emailChannel,
            SmsNotificationChannel smsChannel,
            InAppNotificationChannel inAppChannel,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.contentGenerator = contentGenerator;
        this.notificationRepository = notificationRepository;
        this.preferencesRepository = preferencesRepository;
        this.redisRepository = redisRepository;
        this.pushChannel = pushChannel;
        this.emailChannel = emailChannel;
        this.smsChannel = smsChannel;
        this.inAppChannel = inAppChannel;
        this.observationRegistry = observationRegistry;

        this.rateLimitedCounter =
                Counter.builder("notification.rate.limited.total")
                        .register(meterRegistry);
        this.dedupSkippedCounter =
                Counter.builder("notification.dedup.skipped.total")
                        .register(meterRegistry);
        this.quietHoursDeferredCounter =
                Counter.builder("notification.quiet.hours.deferred.total")
                        .register(meterRegistry);
    }

    /**
     * Main processing pipeline.
     * Returns true if notification was delivered or deferred.
     * Returns false if skipped (dedup, rate limit).
     */
    public boolean process(NotificationEventType eventType,
                           String userId,
                           String eventId,
                           Map<String, Object> eventContext,
                           String traceId,
                           String sourceTopic) {

        Observation obs = Observation.createNotStarted(
                        "notification.process", observationRegistry)
                .lowCardinalityKeyValue("eventType", eventType.name())
                .start();

        try (Observation.Scope scope = obs.openScope()) {

            // ── Step 1: Deduplication ─────────────────────
            boolean isNew = redisRepository.checkAndSetDedup(
                    userId, eventType, eventId);

            if (!isNew) {
                dedupSkippedCounter.increment();
                log.debug("Dedup skip: userId={} eventId={}",
                        userId, eventId);
                return false;
            }

            // ── Step 2: Rate limit ────────────────────────
            boolean isUrgent = isUrgentEventType(eventType);

            if (!isUrgent) {
                boolean withinLimit =
                        redisRepository.checkRateLimit(userId);
                if (!withinLimit) {
                    rateLimitedCounter.increment();
                    log.warn("Rate limit: userId={} eventType={}",
                            userId, eventType);
                    return false;
                }
            }

            // ── Step 3: Concurrent data gathering (Virtual Threads) ─
            UserNotificationPreferences prefs;
            try (ExecutorService executor =
                         Executors.newVirtualThreadPerTaskExecutor()) {

                Future<UserNotificationPreferences> prefsFuture =
                        executor.submit(() -> loadPreferences(userId));

                prefs = prefsFuture.get(5, TimeUnit.SECONDS);

            } catch (TimeoutException e) {
                log.warn("Preferences loading timed out, using defaults");
                prefs = buildDefaultPreferences(userId);
            } catch (Exception e) {
                log.warn("Data gathering failed, using defaults: {}",
                        e.getMessage());
                prefs = buildDefaultPreferences(userId);
            }

            // ── Step 4: Quiet hours check ─────────────────
            if (prefs.getQuietHours() != null &&
                    prefs.getQuietHours().isQuietNow(
                            ZonedDateTime.now(ZoneId.of(
                                    prefs.getTimezone() != null
                                            ? prefs.getTimezone()
                                            : "America/Mexico_City")))
                    && !isUrgent) {

                quietHoursDeferredCounter.increment();
                log.debug("Quiet hours deferred: userId={}",
                        userId);
                // In production: schedule for end of quiet hours
                return true; // treated as processed
            }

            // ── Step 5: AI content generation ─────────────
            long genStart = System.currentTimeMillis();
            NotificationContent content =
                    contentGenerator.generate(
                            eventType, eventContext, prefs);
            long genDuration = System.currentTimeMillis() - genStart;

            // ── Step 6: Channel delivery ───────────────────
            Map<String, NotificationDocument.ChannelDeliveryStatus>
                    channelResults = new HashMap<>();

            // IN_APP always delivered
            channelResults.put("IN_APP",
                    inAppChannel.send(content, prefs, userId));

            // Conditional channels based on preferences + event type
            List<NotificationChannel> additionalChannels =
                    selectAdditionalChannels(eventType, prefs, content);

            for (NotificationChannel channel : additionalChannels) {
                var result = switch (channel) {
                    case PUSH -> pushChannel.send(
                            content, prefs, userId);
                    case EMAIL -> emailChannel.send(
                            content, prefs, userId);
                    case SMS -> smsChannel.send(
                            content, prefs, userId);
                    default -> null;
                };
                if (result != null) {
                    channelResults.put(channel.name(), result);
                }
            }

            // ── Step 7: Persist notification ──────────────
            String overallStatus = channelResults.values().stream()
                    .anyMatch(r -> "DELIVERED".equals(r.status()))
                    ? "DELIVERED" : "FAILED";

            NotificationDocument doc = NotificationDocument.builder()
                    .notificationId(UUID.randomUUID().toString())
                    .userId(userId)
                    .eventType(eventType)
                    .eventId(eventId)
                    .content(content)
                    .contentGenerationMethod(
                            determineGenerationMethod(content))
                    .contentGenerationDurationMs(genDuration)
                    .channels(channelResults)
                    .overallStatus(overallStatus)
                    .priority(isUrgent ? "HIGH" : "NORMAL")
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now()
                            .plus(Duration.ofDays(
                                    isUrgent ? 365 : 90)))
                    .isRead(false)
                    .metadata(NotificationDocument.NotificationMetadata
                            .builder()
                            .sourceService("kafka")
                            .kafkaTopic(sourceTopic)
                            .traceId(traceId)
                            .build())
                    .build();

            notificationRepository.save(doc);

            obs.event(Observation.Event.of(
                    "notification.processed." + overallStatus));

            log.info("Notification processed: userId={} " +
                            "eventType={} status={} channels={}",
                    userId, eventType, overallStatus,
                    channelResults.keySet());

            return true;

        } finally {
            obs.stop();
        }
    }

    // ── Private helpers ───────────────────────────────────

    private boolean isUrgentEventType(
            NotificationEventType eventType) {
        return switch (eventType) {
            case FRAUD_ALERT, SECURITY_ALERT,
                 ACCOUNT_FROZEN -> true;
            default -> false;
        };
    }

    private List<NotificationChannel> selectAdditionalChannels(
            NotificationEventType eventType,
            UserNotificationPreferences prefs,
            NotificationContent content) {

        List<NotificationChannel> channels = new ArrayList<>();

        // Push: if enabled
        if (prefs.getPushConfig() != null &&
                prefs.getPushConfig().enabled()) {
            channels.add(NotificationChannel.PUSH);
        }

        // Urgent: always add all available channels
        if (isUrgentEventType(eventType)) {
            if (prefs.getEmailConfig() != null &&
                    prefs.getEmailConfig().enabled()) {
                channels.add(NotificationChannel.EMAIL);
            }
            if (prefs.getSmsConfig() != null &&
                    prefs.getSmsConfig().enabled()) {
                channels.add(NotificationChannel.SMS);
            }
        }

        return channels;
    }

    private UserNotificationPreferences loadPreferences(
            String userId) {
        return preferencesRepository.findById(userId)
                .orElse(buildDefaultPreferences(userId));
    }

    private UserNotificationPreferences buildDefaultPreferences(
            String userId) {
        return UserNotificationPreferences.builder()
                .userId(userId)
                .language("es")
                .timezone("America/Mexico_City")
                .globalOptOut(false)
                .inAppConfig(UserNotificationPreferences.ChannelConfig
                        .builder().enabled(true).build())
                .build();
    }

    private String determineGenerationMethod(
            NotificationContent content) {
        // If templateFallback is non-empty but highlights is empty,
        // likely used fallback
        return (content.highlights() != null &&
                !content.highlights().isEmpty())
                ? "AI" : "FALLBACK";
    }
}