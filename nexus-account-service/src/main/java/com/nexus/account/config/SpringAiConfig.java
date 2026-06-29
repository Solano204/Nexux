package com.nexus.account.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean("accountAdvisorClient")
    public ChatClient accountAdvisorClient(
            OpenAiChatModel chatModel,
            PgVectorStore transactionVectorStore,
            ChatMemory chatMemory) {

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

        MessageChatMemoryAdvisor windowMemory =
                MessageChatMemoryAdvisor.builder(chatMemory)
                        .build();

        VectorStoreChatMemoryAdvisor semanticMemory =
                VectorStoreChatMemoryAdvisor.builder(transactionVectorStore)
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
                        windowMemory,
                        semanticMemory,
                        ragAdvisor
                )
                .build();
    }

    @Bean
    public ChatMemory accountAdvisorChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();
    }
}
