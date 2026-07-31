package com.nexus.notification.application.ai;

import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.enums.NotificationEventType;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Notification LLM Gateway - the OpenAI call site for content generation,
 * split out of NotificationContentGenerator so @Retry actually applies.
 *
 * It was previously a private method called from within the same class
 * (self-invocation) - that bypasses the Spring AOP proxy entirely, so the
 * retry/fallback never triggered on failure. Living in its own bean means
 * NotificationContentGenerator calls it through the proxy instead.
 */
@Slf4j
@Component
public class NotificationLlmGateway {

    private final ChatClient chatClient;
    private final FallbackContentGenerator fallbackGenerator;

    public NotificationLlmGateway(
            @Qualifier("notificationChatClient") ChatClient chatClient,
            FallbackContentGenerator fallbackGenerator) {
        this.chatClient = chatClient;
        this.fallbackGenerator = fallbackGenerator;
    }

    @Retry(name = "openai-retry", fallbackMethod = "generateFallback")
    public NotificationContent generate(
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
                buildFallbackContext(eventContext));
    }

    private Map<String, String> buildFallbackContext(
            Map<String, Object> ctx) {
        var result = new java.util.HashMap<String, String>();
        ctx.forEach((k, v) -> result.put(k, String.valueOf(v)));
        return result;
    }
}
