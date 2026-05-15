package com.nexus.analytics.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring AI Configuration — Analytics Insights.
 *
 * Two clients, one purpose:
 *
 * insightsChatClient — generates spending insights.
 * - gpt-4o-mini: cheapest, fast, sufficient for insight generation
 * - temperature=0.4: some creativity in phrasing, not random
 * - JSON response format: structured FinancialInsight output
 * - System prompt: from .st template file (Section 2 pattern)
 *
 * anomalyChatClient — chain-of-thought anomaly analysis.
 * - gpt-4o-mini with temperature=0.2: more deterministic for analysis
 * - System prompt from separate .st file (Section 4 CoT pattern)
 *
 * Both use one-shot examples embedded in user message templates.
 * This is the Section 2 foundational pattern — no RAG, no tools,
 * no agents. Pure prompt engineering for high-quality output.
 */
@Configuration
public class SpringAiConfig {

    @Bean("insightsChatClient")
    public ChatClient insightsChatClient(OpenAiChatModel model)
            throws IOException {

        String systemPrompt = loadTemplate(
                "templates/analytics/spending-insights-system.st");

        return ChatClient.builder(model)
                .defaultSystem(systemPrompt)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.4)
                        .maxTokens(2000)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean("anomalyChatClient")
    public ChatClient anomalyChatClient(OpenAiChatModel model)
            throws IOException {

        String systemPrompt = loadTemplate(
                "templates/analytics/anomaly-insights-system.st");

        return ChatClient.builder(model)
                .defaultSystem(systemPrompt)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.2)
                        .maxTokens(800)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    private String loadTemplate(String path) throws IOException {
        return new ClassPathResource(path)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}