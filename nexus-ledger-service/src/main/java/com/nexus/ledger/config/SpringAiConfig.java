package com.nexus.ledger.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean("ledgerExplainerClient")
    public ChatClient ledgerExplainerClient(
            OpenAiChatModel model,
            PgVectorStore financialLiteracyVectorStore,
            InMemoryChatMemory explainerMemory,
            com.nexus.ledger.infrastructure.ai.LedgerExplainerTools tools) {

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
                        .queryAugmenter(
                                ContextualQueryAugmenter.builder()
                                        .allowEmptyContext(true)
                                        .build())
                        .build();

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
                        - Reference specific amounts and dates from the data
                        - Respond in the same language as the user
                        """)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.7)
                        .maxTokens(1500)
                        .build())
                .defaultTools(tools)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        memoryAdvisor,
                        ragAdvisor
                )
                .build();
    }

    @Bean
    public InMemoryChatMemory explainerMemory() {
        return new InMemoryChatMemory();
    }
}