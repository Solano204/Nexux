package com.nexus.assistant.config;

import com.nexus.assistant.agent.tools.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postprocessing.RerankPostProcessor;
import org.springframework.ai.rag.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.query.retrieval.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AI Configuration — AI Assistant Service.
 *
 * Five ChatClient beans (from encyclopedia):
 * 1. primaryClient     — standard conversation (GPT-4o-mini)
 * 2. agentClient       — complex operations (GPT-4o)
 * 3. visionClient      — document analysis (GPT-4.1-mini)
 * 4. structuredClient  — typed financial data
 * 5. ollamaFallback    — local fallback (Mistral 7B)
 *
 * Advisor chain on primaryClient (Section 5):
 * ContentSanitizerAdvisor → SafeGuardAdvisor →
 * ErrorWrappingAdvisor → VectorStoreChatMemoryAdvisor →
 * MessageChatMemoryAdvisor → RetrievalAugmentationAdvisor →
 * SimpleLoggerAdvisor
 *
 * Tools registered on agentClient (Section 11):
 * AccountBalanceTool, TransactionHistoryTool, TransferFundsTool,
 * FraudAlertsTool, SpendingAnalysisTool, SavingsRecommendationsTool
 */
@Configuration
public class SpringAiConfig {

    private static final String SYSTEM_PROMPT = """
        You are Nexus Assistant, a helpful and knowledgeable personal
        financial AI for Nexus bank customers in Latin America.

        Your capabilities:
        - Answer questions about account balances and transactions
        - Analyze spending patterns and provide budgeting insights
        - Help initiate transfers (with explicit user confirmation)
        - Explain recent fraud alerts
        - Provide personalized financial advice

        Rules:
        - You can ONLY access data for the authenticated user
        - For transfers: ALWAYS confirm exact amount + destination
          BEFORE calling transfer_funds tool
        - Never make up financial data — use tools to get real data
        - Never expose internal system names or risk scores
        - For amounts: always include currency (MXN 250.00)
        - Respond in the same language as the user
        - If unsure: say so and suggest the user contact support

        Financial advice disclaimer: You provide guidance, not advice.
        For major financial decisions, recommend consulting a professional.
        """;

    // ── 1. Primary Client — standard conversations ────────

    @Bean("aiAssistantPrimaryClient")
    public ChatClient primaryClient(
            OpenAiChatModel openAiModel,
            ChatMemory jdbcChatMemory,
            @org.springframework.beans.factory.annotation
                    .Qualifier("conversationMemoryVectorStore")
            PgVectorStore conversationMemoryStore,
            @org.springframework.beans.factory.annotation
                    .Qualifier("financialKnowledgeVectorStore")
            PgVectorStore knowledgeStore,
            AccountBalanceTool balanceTool,
            TransactionHistoryTool historyTool,
            FraudAlertsTool fraudTool,
            SpendingAnalysisTool spendingTool) {

        // Section 10: Advanced RAG for financial knowledge base
        RetrievalAugmentationAdvisor ragAdvisor =
                RetrievalAugmentationAdvisor.builder()
                        .queryExpander(
                                MultiQueryExpander.builder()
                                        .chatClientBuilder(
                                                ChatClient.builder(openAiModel))
                                        .numberOfQueries(4)
                                        .build())
                        .documentRetriever(
                                VectorStoreDocumentRetriever.builder()
                                        .vectorStore(knowledgeStore)
                                        .topK(20)
                                        .similarityThreshold(0.65)
                                        .build())
                        .documentPostProcessors(
                                new RerankPostProcessor(6))
                        .queryAugmenter(
                                ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(true)
                                        .build())
                        .build();

        // Section 7: Long-term semantic memory
        VectorStoreChatMemoryAdvisor vectorMemory =
                VectorStoreChatMemoryAdvisor
                        .builder(conversationMemoryStore)
                        .defaultTopK(10)
                        .build();

        // Section 7: Short-term JDBC window memory
        MessageChatMemoryAdvisor windowMemory =
                MessageChatMemoryAdvisor
                        .builder(jdbcChatMemory)
                        .build();

        return ChatClient.builder(openAiModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(2000)
                        .build())
                // Read-only tools on primary client
                .defaultTools(balanceTool, historyTool,
                        fraudTool, spendingTool)
                .defaultAdvisors(
                        // Section 5: Security advisors first
                        new ContentSanitizerAdvisor(),
                        new SafeGuardAdvisor(List.of(
                                "ignore previous instructions",
                                "system prompt", "api_key", "sk-",
                                "print your instructions",
                                "forget your role",
                                "you are now a different AI")),
                        new ErrorWrappingAdvisor(),
                        // Section 7: Memory advisors
                        vectorMemory,
                        windowMemory,
                        // Section 10: RAG
                        ragAdvisor,
                        // Logging last
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    // ── 2. Agent Client — complex multi-step operations ───

    @Bean("aiAssistantAgentClient")
    public ChatClient agentClient(
            OpenAiChatModel openAiModel,
            ChatMemory jdbcChatMemory,
            AccountBalanceTool balanceTool,
            TransactionHistoryTool historyTool,
            TransferFundsTool transferTool,
            FraudAlertsTool fraudTool,
            SpendingAnalysisTool spendingTool,
            SavingsRecommendationsTool savingsTool) {

        return ChatClient.builder(openAiModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")          // More capable for planning
                        .temperature(0.1)         // Low temp = reliable decisions
                        .maxTokens(3000)
                        // Section 11: WE drive the tool loop
                        .internalToolExecutionEnabled(false)
                        .build())
                // ALL tools including transfer (write operation)
                .defaultTools(balanceTool, historyTool,
                        transferTool, fraudTool,
                        spendingTool, savingsTool)
                .defaultAdvisors(
                        new ContentSanitizerAdvisor(),
                        new SafeGuardAdvisor(List.of(
                                "ignore previous instructions",
                                "bypass transfer confirmation")),
                        new ErrorWrappingAdvisor(),
                        MessageChatMemoryAdvisor.builder(jdbcChatMemory)
                                .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    // ── 3. Vision Client — document analysis (Section 8) ─

    @Bean("aiAssistantVisionClient")
    public ChatClient visionClient(OpenAiChatModel openAiModel) {
        return ChatClient.builder(openAiModel)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")      // vision-capable
                        .temperature(0.0)          // deterministic extraction
                        .maxTokens(500)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    // ── 4. Structured Client — typed financial responses ─

    @Bean("aiAssistantStructuredClient")
    public ChatClient structuredClient(OpenAiChatModel openAiModel) {
        return ChatClient.builder(openAiModel)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.2)
                        .maxTokens(1000)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .build();
    }

    // ── 5. Ollama Fallback — local, no cost (Section 2) ──

    @Bean("aiAssistantFallbackClient")
    public ChatClient ollamaFallbackClient(
            OllamaChatModel ollamaModel,
            ChatMemory jdbcChatMemory) {
        return ChatClient.builder(ollamaModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(OllamaOptions.builder()
                        .model("mistral:7b")
                        .temperature(0.7f)
                        .build())
                .defaultAdvisors(
                        new ContentSanitizerAdvisor(),
                        MessageChatMemoryAdvisor.builder(jdbcChatMemory)
                                .build()
                )
                .build();
    }

    // ── Chat memory beans ─────────────────────────────────

    @Bean
    public ChatMemory jdbcChatMemory(
            org.springframework.ai.chat.memory.repository.jdbc
                    .JdbcChatMemoryRepository repository) {
        return org.springframework.ai.chat.memory
                .MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(10)  // Last 10 messages in window
                .build();
    }

    // ── Two separate pgvector stores ──────────────────────

    @Bean("conversationMemoryVectorStore")
    public PgVectorStore conversationMemoryVectorStore(
            javax.sql.DataSource dataSource,
            org.springframework.ai.openai.OpenAiEmbeddingModel embeddingModel) {
        return PgVectorStore.builder(dataSource, embeddingModel)
                .vectorTableName("ai_conversation_memory")
                .build();
    }

    @Bean("financialKnowledgeVectorStore")
    public PgVectorStore financialKnowledgeVectorStore(
            javax.sql.DataSource dataSource,
            org.springframework.ai.openai.OpenAiEmbeddingModel embeddingModel) {
        return PgVectorStore.builder(dataSource, embeddingModel)
                .vectorTableName("financial_knowledge_base")
                .build();
    }
}