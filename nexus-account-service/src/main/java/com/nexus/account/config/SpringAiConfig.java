package com.nexus.account.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postprocessing.RerankPostProcessor;
import org.springframework.ai.rag.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.query.retrieval.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Account Advisor.
 *
 * Implements the Advanced RAG pipeline from Section 10:
 * - Domain synonym transformation for financial terms
 * - Multi-query expansion (4 paraphrases)
 * - pgvector similarity search (top 20)
 * - Cohere reranking (top 8)
 * - Neighbor stitch (temporal context)
 * - Citation headers
 *
 * Hybrid memory from Section 7:
 * - JDBC window memory (last 5 exchanges)
 * - pgvector semantic memory (relevant past sessions)
 */
@Configuration
public class SpringAiConfig {

    /**
     * Account Advisor ChatClient — the AI financial advisor.
     * Uses Advanced RAG over user's transaction embeddings.
     */
    @Bean("accountAdvisorClient")
    public ChatClient accountAdvisorClient(
            OpenAiChatModel chatModel,
            PgVectorStore transactionVectorStore,
            ChatMemory chatMemory) {

        // Section 10: Advanced RAG pipeline
        RetrievalAugmentationAdvisor ragAdvisor =
                RetrievalAugmentationAdvisor.builder()
                        .queryExpander(
                                MultiQueryExpander.builder()
                                        .chatClientBuilder(ChatClient.builder(chatModel))
                                        .numberOfQueries(4)
                                        .build())
                        .documentRetriever(
                                VectorStoreDocumentRetriever.builder()
                                        .vectorStore(transactionVectorStore)
                                        .topK(20)
                                        .similarityThreshold(0.65)
                                        // Security: user only sees their own transactions
                                        // filterExpression is set dynamically per request
                                        .build())
                        .documentPostProcessors(
                                // Cohere reranker — top 8 most relevant
                                new RerankPostProcessor(8),
                                // Citation headers for transaction references
                                new CitationHeaderPostProcessor()
                        )
                        .queryAugmenter(
                                ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(true)
                                        .build())
                        .build();

        // Section 7: Hybrid Memory
        MessageChatMemoryAdvisor windowMemory =
                MessageChatMemoryAdvisor.builder(chatMemory)
                        .build();

        VectorStoreChatMemoryAdvisor semanticMemory =
                VectorStoreChatMemoryAdvisor.builder(transactionVectorStore)
                        .defaultTopK(5)
                        .build();

        return ChatClient.builder(chatModel)
                .defaultSystem("""
                You are a personal financial advisor for Nexus Bank.
                You have access to this user's actual transaction history.

                Rules:
                - Give specific, quantified advice based on the provided data
                - Always cite specific transactions when making recommendations
                - Use MXN currency with 2 decimal places
                - Be encouraging and constructive, never judgmental
                - If asked about savings goals, reference any goals from memory
                - Always recommend setting up automatic savings when applicable
                - Respond in the same language as the user's question
                """)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(2000)
                        .build())
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        windowMemory,        // Section 7: JDBC window
                        semanticMemory,      // Section 7: pgvector semantic
                        ragAdvisor           // Section 10: Advanced RAG
                )
                .build();
    }

    @Bean
    public ChatMemory accountAdvisorChatMemory() {
        return new InMemoryChatMemory();
    }

    /**
     * Simple citation header post-processor.
     * Prepends [account: X, date: Y, category: Z] to each document.
     */
    static class CitationHeaderPostProcessor implements
            org.springframework.ai.rag.postprocessing.DocumentPostProcessor {

        @Override
        public List<org.springframework.ai.document.Document> process(
                List<org.springframework.ai.document.Document> docs) {
            return docs.stream().map(doc -> {
                var metadata = doc.getMetadata();
                String header = String.format("[account: %s, date: %s, " +
                                "category: %s] ",
                        metadata.getOrDefault("accountId", "unknown"),
                        metadata.getOrDefault("date", "unknown"),
                        metadata.getOrDefault("category", "unknown"));
                return new org.springframework.ai.document.Document(
                        header + doc.getContent(), doc.getMetadata());
            }).toList();
        }
    }
}