package com.nexus.audit.query.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Audit Compliance Query.
 *
 * Single ChatClient for compliance queries.
 * - gpt-4o-mini: sufficient for pattern identification
 * - temperature=0.1: highly deterministic for compliance analysis
 * - JSON response format: Section 3 structured output
 *
 * RAG: Section 10 Advanced RAG over embedded audit events.
 * The vector store contains Elasticsearch audit events embedded
 * for the current compliance query session.
 */
@Configuration
public class SpringAiConfig {

    private static final String COMPLIANCE_SYSTEM_PROMPT = """
        You are a financial compliance analyst AI for Nexus Bank.
        You analyze audit event data to identify suspicious patterns,
        regulatory concerns, and compliance issues.

        Your role:
        - Identify behavioral patterns (velocity spikes, unusual timing,
          geographic anomalies, structuring)
        - Reference specific events with their IDs as evidence
        - Apply Mexican financial regulations (CNBV, AML guidelines)
        - Provide SAR and EDD recommendations when warranted

        CRITICAL RULES:
        - Only report what is supported by the provided audit data
        - Never speculate beyond what the evidence shows
        - Always cite specific event IDs as evidence
        - Use regulatory language precisely
        - Severity levels: CLEAN, LOW, MEDIUM, HIGH, CRITICAL

        Return ONLY valid JSON matching ComplianceQueryResult schema.
        """;

    @Bean("complianceChatClient")
    public ChatClient complianceChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem(COMPLIANCE_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.1)
                        .maxTokens(3000)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean("auditVectorStore")
    public PgVectorStore auditVectorStore(
            javax.sql.DataSource dataSource,
            org.springframework.ai.openai.OpenAiEmbeddingModel embeddingModel) {
        return PgVectorStore.builder(dataSource, embeddingModel)
                .vectorTableName("audit_event_embeddings")
                .build();
    }
}