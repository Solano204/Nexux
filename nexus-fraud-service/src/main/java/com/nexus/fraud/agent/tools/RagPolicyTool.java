package com.nexus.fraud.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG Policy Tool — Retrieves fraud detection policies from pgvector.
 * Realigned to pass strict Spring AI 1.0.0-M6 compilation rules.
 */
@Slf4j
@Component
public class RagPolicyTool {

    private final PgVectorStore policyVectorStore;
    private final MultiQueryExpander queryExpander;
    private final RerankPostProcessor reranker;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    public RagPolicyTool(
            PgVectorStore policyVectorStore,
            OpenAiChatModel chatModel, // ✅ Injected ChatModel directly to eliminate extraction hacks
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry) {
        this.policyVectorStore = policyVectorStore;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        // Realigned M6 initialization hook using clean parameters
        this.queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(4)
                .build();

        this.reranker = new RerankPostProcessor(6);
    }

    @Tool(
            name = "rag_policy_tool",
            description = """
            Retrieves fraud detection policies and regulatory requirements
            from the internal knowledge base using semantic search.
            MANDATORY for every transaction analysis.
            """
    )
    public String queryFraudPolicies(
            @ToolParam(description = "Detected signals to search policies for.") String signalsContext,
            @ToolParam(description = "Transaction type for policy filtering") String transactionType,
            @ToolParam(description = "Amount as string for threshold check") String amount,
            @ToolParam(description = "Currency code") String currency) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.rag_policy.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            String queryText = buildPolicyQuery(signalsContext, transactionType, amount, currency);
            log.debug("RAG policy query: {}", queryText);

            VectorStoreDocumentRetriever retriever =
                    VectorStoreDocumentRetriever.builder()
                            .vectorStore(policyVectorStore)
                            .topK(15)
                            .similarityThreshold(0.60)
                            .build();

            Query query = Query.builder()
                    .text(queryText)
                    .build();

            List<Query> expandedQueries = queryExpander.expand(query);
            List<Document> retrieved = retriever.retrieve(expandedQueries.get(0));

            List<Document> reranked = reranker.apply(retrieved, query);

            List<PolicyFragment> fragments = reranked.stream()
                    .map(doc -> {
                        var meta = doc.getMetadata();
                        return new PolicyFragment(
                                String.valueOf(meta.getOrDefault("policy_id", "UNKNOWN")),
                                String.valueOf(meta.getOrDefault("policy_title", "Unknown Policy")),
                                String.valueOf(meta.getOrDefault("section", "")),
                                doc.getText(),
                                0.0
                        );
                    })
                    .toList();

            String status = fragments.isEmpty() ? "NO_POLICY_FOUND" : "POLICIES_RETRIEVED";

            var result = new RagPolicyResult(signalsContext, fragments, status);
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("RAG policy retrieval failed: {}", e.getMessage(), e);
            return """
                {"status": "RETRIEVAL_FAILED", "error": "%s", "fragments": []}
                """.formatted(e.getMessage());
        } finally {
            obs.stop();
        }
    }

    private String buildPolicyQuery(String signals, String txnType, String amount, String currency) {
        return String.format("%s %s transaction %s %s fraud detection policy", signals, txnType, amount, currency);
    }

    public record PolicyFragment(String policyId, String policyTitle, String section, String content, double score) {}
    public record RagPolicyResult(String query, List<PolicyFragment> fragments, String status) {}
}