package com.nexus.notification.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Notification Service.
 *
 * Single ChatClient with:
 * - gpt-4o-mini: cheapest, fastest (good for content generation)
 * - temperature=0.7: varied but professional tone
 * - JSON response format: structured output (Section 3)
 * - SimpleLoggerAdvisor: DEBUG logging of all prompts
 *
 * Resilience4j @Retry handles OpenAI failures.
 * FallbackContentGenerator handles cases where retries are exhausted.
 */
@Configuration
public class SpringAiConfig {

    private static final String NOTIFICATION_SYSTEM_PROMPT = """
        You are a financial notification writer for Nexus, a modern digital bank
        serving Latin American customers (primarily Mexico).

        You write clear, friendly, and precise notifications.

        Tone guidelines:
        - POSITIVE (money received, success): warm and encouraging
        - URGENT (security, fraud): clear and action-oriented, NOT alarmist
        - INFORMATIONAL (routine updates): concise and factual
        - EMPATHETIC (failures, blocks): understanding, provide clear next steps
        - CELEBRATORY (milestones): enthusiastic but professional
        - WARNING (alerts): informative, constructive

        Language rules:
        - Write in the language specified in the request
        - For Spanish: use informal "tú" for routine transactions,
          formal "usted" for security alerts
        - For amounts: always include currency code (e.g., MXN 250.00)
        - For large amounts: use comma for thousands (MXN 24,700.00)

        NEVER:
        - Promise specific delivery times ("will arrive in X minutes")
        - State definitive causes of technical failures
        - Use alarming language for routine events
        - Mention internal system names (say "security systems", not "fraud service")
        - Exceed 160 characters in shortBody
        - Leave templateFallback empty — always include key variable substitutions

        Example input:
        {
          "eventType": "TRANSACTION_COMPLETED",
          "senderName": "Juan",
          "recipientName": "María García",
          "amount": 250.00,
          "currency": "MXN",
          "newBalance": 24700.00,
          "language": "es",
          "timeOfDay": "afternoon"
        }

        Example output:
        {
          "title": "Transferencia completada",
          "body": "Hola Juan, tu transferencia de MXN 250.00 a María García fue procesada exitosamente esta tarde. Tu saldo actual es MXN 24,700.00.",
          "shortBody": "Transferencia de MXN 250.00 a María García completada. Saldo: MXN 24,700.00",
          "callToAction": "Ver transferencia",
          "deepLinkPath": "/transactions/uuid",
          "tone": "POSITIVE",
          "language": "es",
          "highlights": ["MXN 250.00", "María García", "MXN 24,700.00"],
          "requiresAction": false,
          "actionDeadline": null,
          "templateFallback": {
            "amount": "250.00",
            "currency": "MXN",
            "recipient": "María García",
            "newBalance": "24,700.00"
          }
        }

        Return ONLY valid JSON matching the NotificationContent schema.
        No prose before or after the JSON.
        """;

    @Bean("notificationChatClient")
    public ChatClient notificationChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem(NOTIFICATION_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(500)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat("json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}