package com.nexus.ledger.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.*;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
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
 * Spring AI Configuration — Ledger Explainer.
 *
 * Advanced RAG pipeline (Section 10):
 * - MultiQueryExpander: 4 paraphrases for financial literacy queries
 * - VectorStoreDocumentRetriever: searches financial glossary
 * - RerankPostProcessor: Cohere reranks top 6
 * - ContextualQueryAugmenter: builds final prompt with context
 *
 * Chat memory (Section 7):
 * - MessageChatMemoryAdvisor: maintains session context
 *
 * Tool calling (Section 11):
 * - get_recent_ledger_entries, get_monthly_summary,
 *   get_category_breakdown (registered on the ChatClient)
 *
 * Streaming SSE (Section 3):
 * - chatClient.stream().content() → Flux<String>
 */
@Configuration
public class SpringAiConfig {

    @Bean("ledgerExplainerClient")
    public ChatClient ledgerExplainerClient(
            OpenAiChatModel model,
            PgVectorStore financialLiteracyVectorStore,
            InMemoryChatMemory explainerMemory,
            com.nexus.ledger.infrastructure.ai.LedgerExplainerTools tools) {

        // Section 10: Advanced RAG for financial literacy context
        RetrievalAugmentationAdvisor ragAdvisor =
                RetrievalAugmentationAdvisor.builder()
                        .queryExpander(
                                MultiQueryExpander.builder()
                                        .chatClientBuilder(ChatClient.builder(model))
                                        .numberOfQueries(4)
                                        .build())
                        .documentRetriever(
                                VectorStoreDocumentRetriever.builder()
                                        .vectorStore(financialLiteracyVectorStore)
                                        .topK(12)
                                        .similarityThreshold(0.60)
                                        .build())
                        .documentPostProcessors(
                                new RerankPostProcessor(5)
                        )
                        .queryAugmenter(
                                ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(true)
                                        .build())
                        .build();

        // Section 7: Memory for multi-turn explain sessions
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(explainerMemory)
                        .build();

        return ChatClient.builder(model)
                .defaultSystem("""
                You are a friendly financial assistant helping bank customers
                understand their transactions and account activity.

                Your role:
                - Translate technical ledger entries into plain language
                - Use simple, everyday words (avoid "debit/credit" unless
                  you explain what they mean)
                - Be encouraging and constructive
                - Reference specific amounts and dates from the data
                - Notice patterns (recurring payments, unusual spending)
                - Answer follow-up questions about specific transactions
                - Respond in the same language as the user

                Format:
                - Use friendly emojis for transaction types (☕ coffee,
                  🏠 rent, 💰 income, 💳 payments)
                - Keep explanations concise but complete
                - Always mention the account balance direction
                  (went up / went down)
                """)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(1500)
                        .build())
                .defaultTools(tools)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        memoryAdvisor,    // Section 7
                        ragAdvisor        // Section 10
                )
                .build();
    }

    @Bean
    public InMemoryChatMemory explainerMemory() {
        return new InMemoryChatMemory();
    }
}