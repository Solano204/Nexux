package com.nexus.notification.domain.model;

import com.nexus.notification.domain.model.enums.NotificationTone;

import java.util.List;
import java.util.Map;

/**
 * NotificationContent — Structured output from Spring AI (Section 3).
 *
 * Every AI response must conform to this schema.
 * Each field serves a specific channel:
 * - title + body → Push + Email
 * - shortBody → SMS (≤160 chars)
 * - deepLinkPath → Push tap action
 * - tone → email template color scheme
 * - templateFallback → used when AI fails
 *
 * Pattern: Structured Output (Section 3)
 */
public record NotificationContent(
        String title,
        String body,
        String shortBody,
        String callToAction,
        String deepLinkPath,
        NotificationTone tone,
        String language,
        List<String> highlights,
        boolean requiresAction,
        String actionDeadline,
        Map<String, String> templateFallback
) {

    /** Convenience: is this an urgent notification? */
    public boolean isUrgent() {
        return tone == NotificationTone.URGENT;
    }

    private static final String SMS_PREFIX = "NEXUS: ";

    /** SMS-safe body — truncated at 157 chars + "..." if needed */
    public String smsBody() {
        String body = SMS_PREFIX + (shortBody != null ? shortBody : title);
        if (body.length() > 157) {
            // Truncate at last word boundary, but never into/before the
            // "NEXUS: " prefix - a shortBody with no spaces at all (one
            // long word) would otherwise match the prefix's own space and
            // collapse the whole message to "NEXUS:...".
            body = body.substring(0, 157);
            int lastSpace = body.lastIndexOf(' ');
            if (lastSpace >= SMS_PREFIX.length()) body = body.substring(0, lastSpace);
            body = body + "...";
        }
        return body;
    }
}