package com.nexus.notification.domain.model;

import com.nexus.notification.domain.model.enums.NotificationChannel;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "user_notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotificationPreferences {

    @Id
    private String userId;

    private String language;     // "es", "en", "pt"
    private String timezone;     // "America/Mexico_City"

    private boolean globalOptOut;

    private ChannelConfig pushConfig;
    private ChannelConfig emailConfig;
    private ChannelConfig smsConfig;
    private ChannelConfig inAppConfig;

    // Per-event-type overrides
    private Map<String, EventPreference> eventPreferences;

    private QuietHours quietHours;
    private AiPersonalization aiPersonalization;

    private Instant updatedAt;

    @Builder
    public record ChannelConfig(
            boolean enabled,
            List<String> deviceArns,  // push only
            String address,           // email
            String phoneNumber        // SMS
    ) {}

    @Builder
    public record EventPreference(
            List<NotificationChannel> channels,
            boolean enabled,
            boolean quietHoursEnabled,
            boolean cannotDisable
    ) {}

    @Builder
    public record QuietHours(
            boolean enabled,
            int startHour,
            int endHour,
            String timezone,
            boolean exceptUrgent
    ) {
        public boolean isQuietNow(java.time.ZonedDateTime now) {
            if (!enabled) return false;
            int hour = now.getHour();
            if (startHour < endHour) {
                return hour >= startHour && hour < endHour;
            }
            // Overnight quiet hours (e.g., 22:00-08:00)
            return hour >= startHour || hour < endHour;
        }
    }

    @Builder
    public record AiPersonalization(
            boolean enabled,
            String preferredStyle,    // "concise", "detailed"
            boolean includeBalance,
            boolean includeHistoricalContext
    ) {}
}