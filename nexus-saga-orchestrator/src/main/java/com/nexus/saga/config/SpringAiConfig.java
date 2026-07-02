package com.nexus.saga.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Saga Failure Explainer.
 *
 * Single ChatClient with:
 * - gpt-4o-mini: sufficient for failure explanation generation
 * - temperature=0.3: consistent but natural language
 * - JSON response format: Section 3 structured output
 *
 * Usage: entity(SagaFailureExplanation.class)
 *
 * Pattern: Section 3 (Structured Output) only.
 * No RAG, no tools, no agents, no memory.
 * Simple single-call generation with well-crafted system prompt.
 *
 * CRITICAL system prompt rules (enforced in service):
 * - Never reveal internal system names
 * - Never reveal fraud detection methods
 * - Always confirm fund status
 * - Always provide next steps
 */
@Configuration
public class SpringAiConfig {

    private static final String FAILURE_EXPLAINER_SYSTEM = """
        You are a customer service AI for Nexus, a digital bank
        serving Latin American customers.

        Your task: explain why a financial operation failed to the
        user in clear, reassuring, human language.

        CRITICAL RULES — violation is not acceptable:
        - NEVER mention internal system names
          (fraud service, saga orchestrator, ledger, etc.)
        - NEVER reveal how security or fraud detection works
        - NEVER blame the user unless the issue is clearly theirs
          (e.g., genuinely insufficient funds)
        - ALWAYS confirm whether funds are safe / released
        - ALWAYS give at least one specific next step
        - Be warm, empathetic, and brief (2-3 sentences max)

        For Spanish: use informal "tú", warm tone, MXN amounts.
        For English: professional but friendly.

        Return ONLY valid JSON matching SagaFailureExplanation.
        No prose before or after. No markdown.
        """;

    @Bean("sagaFailureExplainerClient")
    public ChatClient sagaFailureExplainerClient(
            OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem(FAILURE_EXPLAINER_SYSTEM)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.3)
                        .maxTokens(600)
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}