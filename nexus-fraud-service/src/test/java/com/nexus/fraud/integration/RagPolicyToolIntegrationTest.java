package com.nexus.fraud.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.agent.tools.RagPolicyTool;
import com.nexus.fraud.agent.tools.RerankPostProcessor;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagPolicyTool sits on top of Spring AI's MultiQueryExpander (real OpenAI
 * chat calls for query expansion) and PgVectorStore (real pgvector
 * similarity search over real embeddings) — reproducing that full chain
 * against Testcontainers would mean either paying for real OpenAI calls
 * (query expansion + embeddings) or reimplementing enough of Spring AI's
 * internal contract that the test would verify the reimplementation, not
 * the tool. Consistent with how every other AI-backed tool in this
 * platform is tested (FraudReActAgentTest mocks FraudLlmGateway
 * entirely, VelocityCheckToolTest asserts graceful degradation rather
 * than driving the real client), this test:
 *   1. Drives the tool's own pure logic directly (buildPolicyQuery,
 *      RerankPostProcessor) — no AI involved, deterministic.
 *   2. Verifies the resilience contract that DOES matter operationally:
 *      when the underlying AI/vector infra fails for any reason, the
 *      tool degrades to a RETRIEVAL_FAILED JSON payload rather than
 *      throwing and taking down the fraud agent's tool-calling loop.
 */
@Tag("integration")
class RagPolicyToolIntegrationTest {

    @Test
    @DisplayName("queryFraudPolicies degrades gracefully to RETRIEVAL_FAILED when the AI/vector chain is unavailable")
    void queryFraudPolicies_infraUnavailable_returnsGracefulFailureJson() {
        PgVectorStore vectorStore = mock(PgVectorStore.class);
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class); // unstubbed query expansion — degrades to the original query, not a failure
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("pgvector unavailable"));

        RagPolicyTool tool = new RagPolicyTool(
                vectorStore, chatModel, new ObjectMapper(), ObservationRegistry.NOOP);

        String result = tool.queryFraudPolicies(
                "velocity anomaly, high amount", "TRANSFER", "50000.00", "MXN");

        assertThat(result).contains("\"status\"");
        assertThat(result).contains("RETRIEVAL_FAILED");
        assertThat(result).contains("\"fragments\": []");
    }

    @Test
    @DisplayName("buildPolicyQuery composes signals, transaction type, amount and currency into one search string")
    void buildPolicyQuery_composesAllInputsIntoSearchString() throws Exception {
        PgVectorStore vectorStore = mock(PgVectorStore.class);
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        RagPolicyTool tool = new RagPolicyTool(
                vectorStore, chatModel, new ObjectMapper(), ObservationRegistry.NOOP);

        Method m = RagPolicyTool.class.getDeclaredMethod(
                "buildPolicyQuery", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        String query = (String) m.invoke(tool, "velocity anomaly", "TRANSFER", "50000.00", "MXN");

        assertThat(query).contains("velocity anomaly", "TRANSFER", "50000.00", "MXN", "fraud detection policy");
    }

    // ── RerankPostProcessor — pure precision-filter logic ────────────────

    @Test
    @DisplayName("RerankPostProcessor truncates to topN, preserving retrieval order")
    void rerankPostProcessor_truncatesToTopN() {
        RerankPostProcessor reranker = new RerankPostProcessor(2);
        List<Document> documents = List.of(
                doc("Policy A"), doc("Policy B"), doc("Policy C"), doc("Policy D"));
        Query query = Query.builder().text("velocity anomaly").build();

        List<Document> result = reranker.apply(documents, query);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("Policy A");
        assertThat(result.get(1).getText()).isEqualTo("Policy B");
    }

    @Test
    @DisplayName("RerankPostProcessor returns everything unchanged when under the topN limit")
    void rerankPostProcessor_returnsAllWhenUnderLimit() {
        RerankPostProcessor reranker = new RerankPostProcessor(10);
        List<Document> documents = List.of(doc("Policy A"), doc("Policy B"));

        List<Document> result = reranker.apply(documents, Query.builder().text("q").build());

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("RerankPostProcessor handles null and empty input without throwing")
    void rerankPostProcessor_handlesNullAndEmptyInput() {
        RerankPostProcessor reranker = new RerankPostProcessor(6);
        Query query = Query.builder().text("q").build();

        assertThat(reranker.apply(null, query)).isEmpty();
        assertThat(reranker.apply(List.of(), query)).isEmpty();
    }

    private Document doc(String title) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(title)
                .metadata(Map.of("policy_id", "NEXUS-TEST-001"))
                .build();
    }
}
