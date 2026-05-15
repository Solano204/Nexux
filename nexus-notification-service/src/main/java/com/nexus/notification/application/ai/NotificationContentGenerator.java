package com.nexus.notification.application.ai;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.UserNotificationPreferences;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import com.nexus.notification.domain.model.enums.NotificationTone;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Notification Content Generator — Spring AI content generation.
 *
 * Implements Section 2 (one-shot prompting) + Section 3 (structured output).
 *
 * Flow:
 * 1. Build context-aware user message from event data + user profile
 * 2. Call gpt-4o-mini with one-shot system prompt
 * 3. entity(NotificationContent.class) parses JSON → typed record
 * 4. On failure: @Retry (3x exponential backoff)
 * 5. On retry exhaustion: FallbackContentGenerator produces template content
 *
 * Temperature 0.7: varied but professional tone.
 * NOT an agent — no tool calling, no RAG, no memory.
 * Simple, fast, reliable one-shot generation.
 */
@Slf4j
@Service
public class NotificationContentGenerator {

    private final ChatClient chatClient;
    private final FallbackContentGenerator fallbackGenerator;
    private final ObservationRegistry observationRegistry;

    private final Timer aiGenerationTimer;
    private final Counter aiSuccessCounter;
    private final Counter fallbackCounter;

    public NotificationContentGenerator(
            @Qualifier("notificationChatClient") ChatClient chatClient,
            FallbackContentGenerator fallbackGenerator,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.chatClient = chatClient;
        this.fallbackGenerator = fallbackGenerator;
        this.observationRegistry = observationRegistry;

        this.aiGenerationTimer =
                Timer.builder("notification.ai.generation.duration")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);

        this.aiSuccessCounter =
                Counter.builder("notification.generated.total")
                        .tag("method", "AI").register(meterRegistry);

        this.fallbackCounter =
                Counter.builder("notification.generated.total")
                        .tag("method", "FALLBACK").register(meterRegistry);
    }

    /**
     * Generates personalized notification content.
     * Retried 3x on OpenAI failure, then falls back to templates.
     */
    public NotificationContent generate(
            NotificationEventType eventType,
            Map<String, Object> eventContext,
            UserNotificationPreferences prefs) {

        Observation obs = Observation.createNotStarted(
                "notification.ai.generate", observationRegistry).start();

        Timer.Sample sample = Timer.start();

        try (Observation.Scope scope = obs.openScope()) {

            String userMessage = buildUserMessage(
                    eventType, eventContext, prefs);

            log.debug("Generating notification: type={} lang={}",
                    eventType, prefs != null ? prefs.getLanguage() : "es");

            NotificationContent content = generateWithRetry(
                    eventType, userMessage, eventContext);

            aiSuccessCounter.increment();
            obs.event(Observation.Event.of("ai.generation.success"));

            log.info("Notification generated: type={} tone={} lang={}",
                    eventType, content.tone(), content.language());

            return content;

        } catch (Exception e) {
            obs.error(e);
            log.warn("AI content generation failed, using fallback: {}",
                    e.getMessage());

            fallbackCounter.increment();

            return fallbackGenerator.generate(eventType,
                    buildFallbackContext(eventContext, prefs));

        } finally {
            sample.stop(aiGenerationTimer);
            obs.stop();
        }
    }

    @Retry(name = "openai-retry",
            fallbackMethod = "generateFallback")
    private NotificationContent generateWithRetry(
            NotificationEventType eventType,
            String userMessage,
            Map<String, Object> eventContext) {

        return chatClient.prompt()
                .user(userMessage)
                .call()
                .entity(NotificationContent.class);
    }

    private NotificationContent generateFallback(
            NotificationEventType eventType,
            String userMessage,
            Map<String, Object> eventContext,
            Exception ex) {

        log.warn("All retries exhausted for notification generation: {}",
                ex.getMessage());
        return fallbackGenerator.generate(eventType,
                buildFallbackContext(eventContext, null));
    }

    /**
     * Builds context-aware user message for the AI.
     * Each event type has specific context fields.
     */
    private String buildUserMessage(
            NotificationEventType eventType,
            Map<String, Object> ctx,
            UserNotificationPreferences prefs) {

        String language = prefs != null && prefs.getLanguage() != null
                ? prefs.getLanguage() : "es";

        String timeOfDay = getTimeOfDay(
                prefs != null ? prefs.getTimezone() : "America/Mexico_City");

        String baseContext = String.format(
                "eventType=%s, language=%s, timeOfDay=%s\n",
                eventType, language, timeOfDay);

        String eventDetails = ctx.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce("", (a, b) -> a + b + "\n");

        return switch (eventType) {
            case TRANSACTION_COMPLETED -> baseContext + """
                Generate a transaction completed notification.
                """ + eventDetails + """
                Tone: POSITIVE (celebratory if first transaction)
                Include: amount, recipient, new balance
                """;

            case TRANSACTION_FAILED -> baseContext + """
                Generate a transaction failed notification.
                """ + eventDetails + """
                Tone: EMPATHETIC — reassure that no funds were debited
                Never reveal technical failure reasons
                """;

            case FRAUD_ALERT -> baseContext + """
                Generate a fraud alert notification.
                """ + eventDetails + """
                Tone: URGENT but REASSURING — their money is safe
                Include: what was blocked, what they should do
                Use "usted" in Spanish (formal — security context)
                """;

            case ACCOUNT_CREATED -> baseContext + """
                Generate a welcome/account created notification.
                """ + eventDetails + """
                Tone: CELEBRATORY for first account, POSITIVE for additional
                Include: account type, last 4 digits
                """;

            case KYC_APPROVED -> baseContext + """
                Generate a KYC approval notification.
                """ + eventDetails + """
                Tone: CELEBRATORY — they just unlocked full banking
                Include: their new daily limit
                """;

            case KYC_REJECTED -> baseContext + """
                Generate a KYC rejection notification.
                """ + eventDetails + """
                Tone: EMPATHETIC — explain what went wrong in plain language
                Include: user-facing reason (not technical codes)
                Include: how to try again
                """;

            default -> baseContext + eventDetails;
        };
    }

    private String getTimeOfDay(String timezone) {
        int hour = LocalTime.now(ZoneId.of(timezone)).getHour();
        if (hour >= 5 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 21) return "evening";
        return "night";
    }

    private Map<String, String> buildFallbackContext(
            Map<String, Object> ctx,
            UserNotificationPreferences prefs) {
        var result = new java.util.HashMap<String, String>();
        ctx.forEach((k, v) -> result.put(k, String.valueOf(v)));
        if (prefs != null) {
            result.put("language", prefs.getLanguage());
        }
        return result;
    }
}