package com.nexus.notification.web.controller;

import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.infrastructure.mongodb.PreferencesRepository;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Preferences Controller — User notification preferences management.
 *
 * GET  /api/v1/notifications/preferences           — Get preferences
 * PUT  /api/v1/notifications/preferences           — Update preferences
 * POST /api/v1/notifications/preferences/device    — Register push device
 * DELETE /api/v1/notifications/preferences/device/{token} — Unregister device
 *
 * Validation: FRAUD_ALERT channel cannot be disabled (regulatory).
 * Cache: Redis preferences cache invalidated on every update.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@RequiredArgsConstructor
public class PreferencesController {

    private final PreferencesRepository preferencesRepository;
    private final NotificationRedisRepository redisRepository;

    /**
     * Get user's notification preferences.
     */
    @GetMapping
    public ResponseEntity<UserNotificationPreferences> getPreferences(
            HttpServletRequest request) {

        String userId = extractUserId(request);

        return preferencesRepository.findByUserId(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Return default preferences for new users
                    var defaults = buildDefaultPreferences(userId);
                    preferencesRepository.save(defaults);
                    return ResponseEntity.ok(defaults);
                });
    }

    /**
     * Update notification preferences.
     * Validates critical channels cannot be disabled.
     * Invalidates Redis cache immediately.
     */
    @PutMapping
    public ResponseEntity<?> updatePreferences(
            @Valid @RequestBody UserNotificationPreferences updated,
            HttpServletRequest request) {

        String userId = extractUserId(request);

        // Security: ensure userId matches authenticated user
        updated.setUserId(userId);
        updated.setUpdatedAt(Instant.now());

        // Validate: FRAUD_ALERT cannot be disabled
        if (updated.getEventPreferences() != null) {
            var fraudPref = updated.getEventPreferences().get("FRAUD_ALERT");
            if (fraudPref != null && !fraudPref.enabled()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "CANNOT_DISABLE_FRAUD_ALERTS",
                        "message", "Security alerts cannot be disabled " +
                                "for regulatory compliance"));
            }
        }

        preferencesRepository.save(updated);

        // Invalidate Redis cache
        redisRepository.invalidatePreferences(userId);

        log.info("Preferences updated: userId={}", userId);
        return ResponseEntity.ok(updated);
    }

    /**
     * Register a push notification device (APNs/FCM token).
     */
    @PostMapping("/device")
    public ResponseEntity<Map<String, Object>> registerDevice(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        String deviceToken = body.get("deviceToken");
        String platform = body.getOrDefault("platform", "FCM");

        if (deviceToken == null || deviceToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MISSING_DEVICE_TOKEN"));
        }

        // In production: call AWS SNS to create endpoint ARN
        String simulatedArn = "arn:aws:sns:us-east-1:000000000000:" +
                platform + "/" + deviceToken.substring(0,
                Math.min(deviceToken.length(), 8));

        var prefs = preferencesRepository.findByUserId(userId)
                .orElse(buildDefaultPreferences(userId));

        // Add device ARN to push config
        var pushConfig = prefs.getPushConfig();
        if (pushConfig == null) {
            pushConfig = UserNotificationPreferences.ChannelConfig
                    .builder()
                    .enabled(true)
                    .deviceArns(new java.util.ArrayList<>())
                    .build();
        }
        if (pushConfig.deviceArns() != null &&
                !pushConfig.deviceArns().contains(simulatedArn)) {
            pushConfig.deviceArns().add(simulatedArn);
        }
        prefs.setPushConfig(pushConfig);
        preferencesRepository.save(prefs);

        redisRepository.invalidatePreferences(userId);

        log.info("Device registered: userId={} platform={}", userId, platform);
        return ResponseEntity.ok(Map.of(
                "deviceArn", simulatedArn,
                "platform", platform,
                "registeredAt", Instant.now().toString()));
    }

    /**
     * Unregister a push device (user logged out).
     */
    @DeleteMapping("/device/{deviceToken}")
    public ResponseEntity<Void> unregisterDevice(
            @PathVariable String deviceToken,
            HttpServletRequest request) {

        String userId = extractUserId(request);

        preferencesRepository.findByUserId(userId).ifPresent(prefs -> {
            if (prefs.getPushConfig() != null &&
                    prefs.getPushConfig().deviceArns() != null) {
                prefs.getPushConfig().deviceArns()
                        .removeIf(arn -> arn.contains(deviceToken));
                preferencesRepository.save(prefs);
                redisRepository.invalidatePreferences(userId);
            }
        });

        log.info("Device unregistered: userId={}", userId);
        return ResponseEntity.ok().build();
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null)
            throw new RuntimeException("Authentication required");
        return userId;
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
                .pushConfig(UserNotificationPreferences.ChannelConfig
                        .builder().enabled(true)
                        .deviceArns(new java.util.ArrayList<>()).build())
                .updatedAt(Instant.now())
                .build();
    }
}