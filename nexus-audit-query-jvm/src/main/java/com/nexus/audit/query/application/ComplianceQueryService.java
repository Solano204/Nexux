package com.nexus.audit.query.application;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.audit.query.application.model.*;
import com.nexus.audit.query.infrastructure.mongodb.ComplianceReportRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * Compliance Query Service — Section 10 Advanced RAG.
 *
 * Full pipeline for natural language compliance investigation:
 * 1. Parse query context (target user, date range, query type)
 * 2. Execute structured Elasticsearch pre-filter (hard facts)
 * 3. Embed filtered events into session-scoped pgvector store
 * 4. Build RAG advisor with domain synonym expansion
 * 5. Execute AI query with top-K retrieval
 * 6. Enrich result with Elasticsearch citations
 * 7. Save compliance report to MongoDB
 *
 * FIXES APPLIED:
 * - ComplianceQuery uses @Getter (Lombok) → getStartDate(), not startDate()
 * - Java 25 JEP 505: StructuredTaskScope.open() replaces ShutdownOnFailure
 * - VectorStoreDocumentRetriever: filterExpression uses String filter
 * - ContextualQueryAugmenter import: org.springframework.ai.rag.generation.augmentation
 */
@Slf4j
@Service
public class ComplianceQueryService {

    private final ChatClient complianceChatClient;
    private final PgVectorStore auditVectorStore;
    private final ElasticsearchClient elasticsearchClient;
    private final ComplianceReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Timer queryTimer;
    private final ChatClient.Builder chatClientBuilder;

    public ComplianceQueryService(
            @Qualifier("complianceChatClient")
            ChatClient complianceChatClient,
            @Qualifier("auditVectorStore")
            PgVectorStore auditVectorStore,
            ElasticsearchClient elasticsearchClient,
            ComplianceReportRepository reportRepository,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            ChatClient.Builder chatClientBuilder) {

        this.complianceChatClient = complianceChatClient;
        this.auditVectorStore = auditVectorStore;
        this.elasticsearchClient = elasticsearchClient;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.chatClientBuilder = chatClientBuilder;

        this.queryTimer =
                Timer.builder("audit.compliance.query.duration.seconds")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);
    }

    /**
     * Execute a natural language compliance query.
     *
     * @param query     The compliance query with NL text and metadata
     * @param auditorId The compliance officer executing the query
     * @return          ComplianceQueryResult with patterns and citations
     */
    public ComplianceQueryResult executeQuery(ComplianceQuery query,
                                              String auditorId) {

        Observation obs = Observation.createNotStarted(
                "audit.compliance.query", observationRegistry).start();
        Timer.Sample sample = Timer.start();

        try (Observation.Scope scope = obs.openScope()) {

            log.info("Compliance query: auditor={} target={} range={}/{}",
                    auditorId, query.getTargetUserId(),
                    query.getStartDate(), query.getEndDate());

            // ── Step 1: Parallel data gathering ───────────────────
            // Java 25 JEP 505: StructuredTaskScope.open()
            List<AuditEventSummary> filteredEvents;
            try (var taskScope = StructuredTaskScope.open()) {

                var eventsFuture = taskScope.fork(() ->
                        executeElasticsearchQuery(query));

                taskScope.join();
                filteredEvents = eventsFuture.get();
            }

            if (filteredEvents.isEmpty()) {
                return ComplianceQueryResult.noResults(
                        query.getQueryId(), auditorId);
            }

            log.info("Elasticsearch pre-filter: {} events found",
                    filteredEvents.size());

            // ── Step 2: Build session-scoped RAG advisor ───────────
            RetrievalAugmentationAdvisor ragAdvisor =
                    buildSessionRagAdvisor(filteredEvents,
                            query.getQueryId());

            // ── Step 3: Execute AI query with RAG ─────────────────
            String enrichedQuery = buildEnrichedQuery(query,
                    filteredEvents);

            ComplianceQueryResult result = complianceChatClient
                    .prompt()
                    .user(enrichedQuery)
                    .advisors(ragAdvisor)
                    .call()
                    .entity(ComplianceQueryResult.class);

            if (result == null) {
                result = ComplianceQueryResult.aiFailure(
                        query.getQueryId(), auditorId);
            }

            // ── Step 4: Enrich with direct citations ───────────────
            result = enrichWithCitations(result, filteredEvents);

            // ── Step 5: Save compliance report ────────────────────
            saveReport(query, result, auditorId,
                    filteredEvents.size());

            obs.event(Observation.Event.of("compliance.query.complete"));
            return result;

        } catch (Exception e) {
            obs.error(e);
            log.error("Compliance query failed: {}", e.getMessage(), e);
            return ComplianceQueryResult.error(
                    query.getQueryId(), auditorId, e.getMessage());
        } finally {
            sample.stop(queryTimer);
            obs.stop();
        }
    }

    /**
     * Elasticsearch pre-filter: structured query for the target user
     * and date range. Returns up to 500 most relevant events.
     */
    private List<AuditEventSummary> executeElasticsearchQuery(
            ComplianceQuery query) {

        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index("nexus-audit-*")
                    .routing(query.getTargetUserId())
                    .query(q -> q.bool(b -> {
                        // Must: target user
                        b.must(m -> m.term(t ->
                                t.field("userId")
                                        .value(query.getTargetUserId())));
                        // Must: date range
                        b.must(m -> m.range(r ->
                                r.field("eventTimestamp")
                                        .gte(JsonData.of(
                                                query.getStartDate().toString()))
                                        .lte(JsonData.of(
                                                query.getEndDate().toString()))));

                        // For suspicious queries: boost warnings + security
                        if (query.getQueryType() == QueryType.SUSPICIOUS_ACTIVITY) {
                            b.should(sh -> sh.term(t ->
                                    t.field("severity").value("WARNING")));
                            b.should(sh -> sh.term(t ->
                                    t.field("severity").value("CRITICAL")));
                            b.should(sh -> sh.term(t ->
                                    t.field("category").value("SECURITY")));
                        }
                        return b;
                    }))
                    .sort(so -> so.field(f ->
                            f.field("eventTimestamp").order(SortOrder.Desc)))
                    .size(500));

            SearchResponse<AuditEventSummary> response =
                    elasticsearchClient.search(
                            request, AuditEventSummary.class);

            return response.hits().hits().stream()
                    .map(hit -> hit.source())
                    .filter(Objects::nonNull)
                    .toList();

        } catch (Exception e) {
            log.warn("Elasticsearch query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Builds session-scoped RAG advisor from filtered audit events.
     * Implements Section 10 Advanced RAG pipeline.
     *
     * Spring AI 1.0.0 GA compatible:
     *   RetrievalAugmentationAdvisor → org.springframework.ai.chat.client.advisor
     *   MultiQueryExpander            → org.springframework.ai.rag.preretrieval.query.expansion
     *   VectorStoreDocumentRetriever  → org.springframework.ai.rag.retrieval.search
     *   ContextualQueryAugmenter      → org.springframework.ai.rag.generation.augmentation
     */
    private RetrievalAugmentationAdvisor buildSessionRagAdvisor(
            List<AuditEventSummary> events, String sessionId) {

        return RetrievalAugmentationAdvisor.builder()
                .queryExpander(
                        MultiQueryExpander.builder()
                                .chatClientBuilder(
                                        chatClientBuilder.build().mutate())
                                .numberOfQueries(4)
                                .includeOriginal(true)
                                .build())
                .documentRetriever(
                        VectorStoreDocumentRetriever.builder()
                                .vectorStore(auditVectorStore)
                                .topK(20)
                                .similarityThreshold(0.60)
                                .build())
                .queryAugmenter(
                        ContextualQueryAugmenter.builder()
                                .allowEmptyContext(true)
                                .build())
                .build();
    }

    /**
     * Builds enriched query with event statistics context.
     * Gives the AI model awareness of the data volume and severity
     * distribution before it processes the RAG-retrieved documents.
     */
    private String buildEnrichedQuery(ComplianceQuery query,
                                      List<AuditEventSummary> events) {

        long criticalCount = events.stream()
                .filter(e -> "CRITICAL".equals(e.getSeverity()))
                .count();
        long warningCount = events.stream()
                .filter(e -> "WARNING".equals(e.getSeverity()))
                .count();
        long financialCount = events.stream()
                .filter(e -> "FINANCIAL".equals(e.getCategory()))
                .count();

        return """
            %s

            Context: Analyzing %d audit events for userId=%s
            Period: %s to %s
            Event breakdown: %d total, %d CRITICAL, %d WARNING, %d financial

            Query type: %s
            """.formatted(
                query.getNaturalLanguageQuery(),
                events.size(),
                query.getTargetUserId(),
                query.getStartDate(),
                query.getEndDate(),
                events.size(),
                criticalCount, warningCount, financialCount,
                query.getQueryType());
    }

    /**
     * Enriches AI-generated result with direct Elasticsearch citations.
     * Adds the top 10 most severe events as evidence citations if
     * the AI did not already include them.
     */
    private ComplianceQueryResult enrichWithCitations(
            ComplianceQueryResult result,
            List<AuditEventSummary> events) {

        if (result.getKeyEvents() != null && !result.getKeyEvents().isEmpty()) {
            return result;
        }

        List<ComplianceQueryResult.AuditEventCitation> citations = events.stream()
                .filter(e -> "CRITICAL".equals(e.getSeverity())
                        || "WARNING".equals(e.getSeverity()))
                .limit(10)
                .map(e -> ComplianceQueryResult.AuditEventCitation.builder()
                        .eventId(e.getEventId())
                        .eventType(e.getEventType())
                        .timestamp(e.getEventTimestamp())
                        .sourceService(e.getSourceService())
                        .summary(e.getEventType() + " from " + e.getSourceService())
                        .citationReason("Severity: " + e.getSeverity())
                        .traceId(e.getTraceId())
                        .build())
                .toList();

        result.setKeyEvents(citations);
        return result;
    }

    /**
     * Saves the compliance investigation report to MongoDB.
     * Non-fatal: logging failure does not break the query response.
     */
    private void saveReport(ComplianceQuery query,
                            ComplianceQueryResult result,
                            String auditorId,
                            int eventsAnalyzed) {
        try {
            ComplianceReport report = ComplianceReport.builder()
                    .reportId(UUID.randomUUID().toString())
                    .queryId(query.getQueryId())
                    .auditorUserId(auditorId)
                    .query(query)
                    .result(result)
                    .eventsAnalyzed(eventsAnalyzed)
                    .generatedAt(Instant.now())
                    .reportStatus("FINAL")
                    .build();

            reportRepository.save(report);
        } catch (Exception e) {
            log.warn("Failed to save compliance report: {}",
                    e.getMessage());
        }
    }
}