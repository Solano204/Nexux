package com.nexus.notification.web.controller;

import com.nexus.notification.domain.exception.UnauthorizedException;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.infrastructure.mongodb.PreferencesRepository;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Notification Preferences", description = "Channel config (email/SMS/push/in-app) and registered push devices for the caller's own user.")
@SecurityRequirement(name = "X-User-Id")
public class PreferencesController {

    private final PreferencesRepository preferencesRepository;
    private final NotificationRedisRepository redisRepository;

    @Operation(summary = "Get my notification preferences", description = "Creates and returns platform defaults on first call for a new user — never 404s.")
    @ApiResponse(responseCode = "200", description = "Preferences retrieved (created with defaults if this is the first call)")
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

    @Operation(
            summary = "Update my notification preferences",
            description = "Full replace, not a partial patch — send the complete preferences object. " +
                    "The userId field is overwritten server-side with the caller's own X-User-Id " +
                    "regardless of what's in the body, so a client can't set another user's " +
                    "preferences by editing the payload. FRAUD_ALERT cannot be disabled — " +
                    "regulatory requirement, enforced here, not just in the UI."
    )
    @ApiResponse(responseCode = "200", description = "Preferences updated")
    @ApiResponse(responseCode = "400", description = "Attempted to disable FRAUD_ALERT")
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

    @Operation(
            summary = "Register a push notification device",
            description = "APNs/FCM token — simulated ARN generation today (real implementation " +
                    "would call AWS SNS to create the platform endpoint, see the method body)."
    )
    @ApiResponse(responseCode = "200", description = "Device registered")
    @ApiResponse(responseCode = "400", description = "Missing deviceToken")
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

    @Operation(summary = "Unregister a push device", description = "Call this on logout — idempotent, no-op if the device wasn't registered.")
    @ApiResponse(responseCode = "200", description = "Device unregistered (or wasn't registered)")
    @DeleteMapping("/device/{deviceToken}")
    public ResponseEntity<Void> unregisterDevice(
            @Parameter(description = "Device push token", required = true)
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
            throw new UnauthorizedException("Authentication required");
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