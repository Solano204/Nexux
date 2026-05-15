package com.nexus.notification.domain.model;

import com.nexus.notification.domain.model.enums.*;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * NotificationDocument — MongoDB document for notification history.
 * TTL: 90 days (routine), 365 days (security), no TTL (regulatory).
 */
@Document(collection = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDocument {

    @Id
    private String notificationId;

    @Indexed
    private String userId;

    private NotificationEventType eventType;
    private String eventId;

    // AI-generated content
    private NotificationContent content;
    private String contentGenerationMethod; // "AI" or "FALLBACK"
    private Long contentGenerationDurationMs;

    // Per-channel delivery status
    private Map<String, ChannelDeliveryStatus> channels;

    private String overallStatus;
    private String priority;

    @Indexed
    private Instant createdAt;

    @Indexed
    private Instant expiresAt; // TTL index

    private boolean isRead;
    private Instant readAt;

    private NotificationMetadata metadata;

    @Builder
    public record ChannelDeliveryStatus(
            String status,
            Instant deliveredAt,
            String providerMessageId,
            String reason,
            int attempts
    ) {}

    @Builder
    public record NotificationMetadata(
            String sourceService,
            String kafkaTopic,
            String traceId
    ) {}
}