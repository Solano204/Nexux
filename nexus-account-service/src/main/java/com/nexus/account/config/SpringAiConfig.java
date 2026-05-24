package com.nexus.account.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringAiConfig — Account Advisor RAG pipeline.
 *
 * Implements the Advanced RAG pipeline from Section 10:
 * - Multi-query expansion (4 paraphrases)
 * - pgvector similarity search (top 20, cosine distance)
 * - Contextual query augmentation (allows empty context)
 *
 * Hybrid memory from Section 7:
 * - In-memory window memory (last 5 exchanges per session)
 * - pgvector semantic memory (relevant past sessions)
 *
 * Spring AI version: 1.0.0-M6
 * Package mappings verified against official 1.0.0-M6 javadoc:
 * - RetrievalAugmentationAdvisor → org.springframework.ai.chat.client.advisor
 * - VectorStoreDocumentRetriever → org.springframework.ai.rag.retrieval.search
 * - MultiQueryExpander → org.springframework.ai.rag.preretrieval.query.expansion
 * - ContextualQueryAugmenter → org.springframework.ai.rag.generation.augmentation
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
                                        .build())
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
                        windowMemory,        // Section 7: window memory
                        semanticMemory,      // Section 7: pgvector semantic
                        ragAdvisor           // Section 10: Advanced RAG
                )
                .build();
    }

    @Bean
    public ChatMemory accountAdvisorChatMemory() {
        return new InMemoryChatMemory();
    }
}