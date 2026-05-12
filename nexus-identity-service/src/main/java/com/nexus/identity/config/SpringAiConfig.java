package com.nexus.identity.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Identity Service.
 *
 * Only one ChatClient here (minimal AI usage — Section 3 only).
 * GPT-4o-mini for KYC rejection code translation.
 * temperature=0.3 for consistent but readable output.
 */
@Configuration
public class SpringAiConfig {

    /**
     * KYC Rejection Explainer Client.
     * GPT-4o-mini: cheap, fast, sufficient for translation.
     * No RAG, no memory, no tools — simple structured output.
     */
    @Bean("kycExplainerClient")
    public ChatClient kycExplainerClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.3)
                        .maxTokens(300)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat("json_object"))
                        .build())
                .build();
    }
}