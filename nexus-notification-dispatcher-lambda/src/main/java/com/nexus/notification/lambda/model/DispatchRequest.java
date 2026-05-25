package com.nexus.notification.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * DispatchRequest — the fully-resolved notification from Plane A.
 *
 * ALL decisions have already been made by nexus-notification-service:
 * which channel, which content, which user, which template.
 * This Lambda's only job: execute delivery.
 *
 * @JsonIgnoreProperties: future Plane A fields don't break this Lambda.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchRequest(
        String dispatchId,           // Idempotency key for this dispatch
        String notificationId,       // MongoDB _id in nexus-notification-service
        String userId,
        String channel,              // EMAIL, SMS, PUSH
        String priority,             // NORMAL, HIGH, CRITICAL
        String eventType,            // TRANSACTION_COMPLETED, FRAUD_ALERT, ...

        // ── EMAIL ─────────────────────────────────────────────
        String toEmail,
        String subject,
        String htmlBody,             // Pre-rendered HTML (if AI-generated)
        String textBody,             // Plain text fallback
        String templateName,         // Template name if Lambda renders
        Map<String, String> templateVariables,
        String tone,                 // POSITIVE, URGENT, WARNING, INFORMATIONAL
        String deepLinkUrl,          // CTA button URL
        String callToAction,         // CTA button text

        // ── SMS ───────────────────────────────────────────────
        String toPhoneNumber,        // E.164 format: +52XXXXXXXXXX
        String smsBody,

        // ── PUSH ──────────────────────────────────────────────
        String pushTitle,
        String pushBody,
        String deepLinkPath,
        String deviceEndpointArn,    // SNS platform endpoint ARN
        String platform,             // IOS or ANDROID
        Map<String, String> pushData,

        // ── COMMON ────────────────────────────────────────────
        String language,
        Instant scheduledFor,
        Map<String, String> metadata
) {}
