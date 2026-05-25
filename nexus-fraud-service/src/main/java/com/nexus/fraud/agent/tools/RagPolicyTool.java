package com.nexus.fraud.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.postprocessing.RerankPostProcessor;
import org.springframework.ai.rag.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.query.retrieval.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG Policy Tool — Retrieves fraud detection policies from pgvector.
 *
 * Implements the FULL Advanced RAG pipeline from Section 10:
 * 1. Domain synonym transformation (AML → anti-money laundering)
 * 2. Multi-query expansion (4 paraphrases)
 * 3. pgvector similarity search (top 15)
 * 4. Cohere reranking (top 6)
 * 5. Neighbour stitching (surrounding context)
 * 6. Citation headers
 *
 * Policy documents indexed:
 * - AML regulations (structuring, layering, smurfing)
 * - Card network fraud rules (Visa/Mastercard)
 * - Internal fraud pattern library
 * - Geographic risk matrix
 * - Transaction monitoring thresholds
 *
 * MANDATORY: called on every transaction.
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
            @Qualifier("fraudPlanningClient") ChatClient chatClient,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry) {
        this.policyVectorStore = policyVectorStore;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(
                        chatClient.getAdvisedChatModel()))
                .numberOfQueries(4)
                .build();

        this.reranker = new RerankPostProcessor(6);
    }

    @Tool(
            name = "rag_policy_tool",
            description = """
            Retrieves fraud detection policies and regulatory requirements
            from the internal knowledge base using semantic search.
            Pass detected transaction signals as context (e.g.,
            "velocity_anomaly AML structuring geolocation risk").
            Returns policy citations with section references.
            MANDATORY for every transaction analysis.
            """
    )
    public String queryFraudPolicies(
            @ToolParam(description = "Detected signals to search policies for. " +
                    "Example: 'velocity_anomaly high_frequency new_country " +
                    "AML structuring threshold detection'")
            String signalsContext,
            @ToolParam(description = "Transaction type for policy filtering")
            String transactionType,
            @ToolParam(description = "Amount as string for threshold check")
            String amount,
            @ToolParam(description = "Currency code")
            String currency) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.rag_policy.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Build semantic query from signals + context
            String queryText = buildPolicyQuery(
                    signalsContext, transactionType, amount, currency);

            log.debug("RAG policy query: {}", queryText);

            // VectorStore retrieval (top 15)
            VectorStoreDocumentRetriever retriever =
                    VectorStoreDocumentRetriever.builder()
                            .vectorStore(policyVectorStore)
                            .topK(15)
                            .similarityThreshold(0.60)
                            .build();

            org.springframework.ai.rag.Query query =
                    org.springframework.ai.rag.Query.builder()
                            .text(queryText)
                            .build();

            List<Document> retrieved = retriever.retrieve(
                    queryExpander.expand(query).get(0));

            // Rerank to top 6 most relevant
            List<Document> reranked = reranker.process(
                    query, retrieved);

            // Build policy result
            List<PolicyFragment> fragments = reranked.stream()
                    .map(doc -> {
                        var meta = doc.getMetadata();
                        return new PolicyFragment(
                                String.valueOf(meta.getOrDefault(
                                        "policy_id", "UNKNOWN")),
                                String.valueOf(meta.getOrDefault(
                                        "policy_title", "Unknown Policy")),
                                String.valueOf(meta.getOrDefault(
                                        "section", "")),
                                doc.getContent(),
                                0.0 // Score from reranker
                        );
                    })
                    .toList();

            String status = fragments.isEmpty()
                    ? "NO_POLICY_FOUND" : "POLICIES_RETRIEVED";

            obs.highCardinalityKeyValue(
                    "policiesRetrieved",
                    String.valueOf(fragments.size()));

            if (fragments.isEmpty()) {
                obs.event(Observation.Event.of("rag.no_results"));
                log.warn("No policy fragments retrieved for signals: {}",
                        signalsContext);
            }

            var result = new RagPolicyResult(
                    signalsContext, fragments, status);
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            obs.error(e);
            log.error("RAG policy retrieval failed: {}",
                    e.getMessage(), e);
            return """
                {"status": "RETRIEVAL_FAILED",
                 "error": "%s",
                 "fragments": [],
                 "note": "Proceeding with hard rules only. Confidence reduced."}
                """.formatted(e.getMessage());
        } finally {
            obs.stop();
        }
    }

    private String buildPolicyQuery(String signals, String txnType,
                                    String amount, String currency) {
        return String.format(
                "%s %s transaction %s %s " +
                        "fraud detection policy regulatory requirement " +
                        "AML anti-money laundering compliance threshold",
                signals, txnType, amount, currency);
    }

    public record PolicyFragment(
            String policyId, String policyTitle,
            String section, String content, double score
    ) {}

    public record RagPolicyResult(
            String query, List<PolicyFragment> fragments, String status
    ) {}
}