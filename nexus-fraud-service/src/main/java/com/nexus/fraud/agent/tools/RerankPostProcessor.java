package com.nexus.fraud.agent.tools;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Custom RerankPostProcessor for Spring AI 1.0.0-M6 Advanced RAG pipelines.
 * Acts as a precision filter layer to rescore and reduce document payloads.
 */
public class RerankPostProcessor implements BiFunction<List<Document>, Query, List<Document>> {

    private final int topN;

    public RerankPostProcessor(int topN) {
        this.topN = topN;
    }

    @Override
    public List<Document> apply(List<Document> documents, Query query) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // Section 10 Precision Boundary: Rescore or drop noise beyond token budget
        // In real-life production, call your Cohere/Jina client API here using query.text()
        return documents.stream()
                .limit(topN)
                .toList();
    }
}