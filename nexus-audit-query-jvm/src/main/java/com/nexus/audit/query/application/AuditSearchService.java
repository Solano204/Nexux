package com.nexus.audit.query.application;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Audit Search Service — structured Elasticsearch queries.
 *
 * Non-AI search: direct ES queries for timeline, trace, alerts.
 * Used by AuditController and ComplianceController for
 * non-NL queries that don't need RAG.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditSearchService {

    private final ElasticsearchClient elasticsearchClient;

    /**
     * Chronological event timeline for a user.
     * Uses routing=userId for shard-level efficiency.
     */
    public Map<String, Object> getUserTimeline(
            String userId, int page, int size,
            String startDate, String endDate) {
        try {
            SearchRequest request = SearchRequest.of(s -> {
                s.index("nexus-audit-*")
                        .routing(userId)
                        .query(q -> q.bool(b -> {
                            b.must(m -> m.term(t ->
                                    t.field("userId").value(userId)));
                            if (startDate != null) {
                                b.must(m -> m.range(r ->
                                        r.field("eventTimestamp")
                                                .gte(JsonData.of(startDate))));
                            }
                            if (endDate != null) {
                                b.must(m -> m.range(r ->
                                        r.field("eventTimestamp")
                                                .lte(JsonData.of(endDate))));
                            }
                            return b;
                        }))
                        .sort(so -> so.field(f ->
                                f.field("eventTimestamp")
                                        .order(SortOrder.Desc)))
                        .from(page * size)
                        .size(size);
                return s;
            });

            SearchResponse<Map> response =
                    elasticsearchClient.search(request, Map.class);

            List<Map> events = response.hits().hits().stream()
                    .map(h -> h.source())
                    .filter(Objects::nonNull)
                    .toList();

            return Map.of(
                    "userId", userId,
                    "events", events,
                    "total", response.hits().total() != null
                            ? response.hits().total().value() : 0,
                    "page", page,
                    "size", size);

        } catch (Exception e) {
            log.error("Timeline query failed: userId={} error={}",
                    userId, e.getMessage());
            return Map.of("error", "TIMELINE_UNAVAILABLE",
                    "userId", userId);
        }
    }

    /**
     * Complete audit trail for one transaction across all services.
     * Correlates via traceId.
     */
    public Map<String, Object> getTransactionTrace(
            String transactionId) {
        try {
            // First find the transaction event to get traceId
            SearchRequest txRequest = SearchRequest.of(s -> s
                    .index("nexus-audit-*")
                    .query(q -> q.term(t ->
                            t.field("resourceId")
                                    .value(transactionId)))
                    .size(1));

            SearchResponse<Map> txResponse =
                    elasticsearchClient.search(txRequest, Map.class);

            if (txResponse.hits().hits().isEmpty()) {
                return Map.of("transactionId", transactionId,
                        "events", List.of());
            }

            Map source = txResponse.hits().hits().get(0).source();
            String traceId = source != null
                    ? String.valueOf(source.get("traceId")) : null;

            if (traceId == null) {
                return Map.of("transactionId", transactionId,
                        "events", List.of(source));
            }

            // Find all events with this traceId
            SearchRequest traceRequest = SearchRequest.of(s -> s
                    .index("nexus-audit-*")
                    .query(q -> q.term(t ->
                            t.field("traceId").value(traceId)))
                    .sort(so -> so.field(f ->
                            f.field("eventTimestamp")
                                    .order(SortOrder.Asc)))
                    .size(100));

            SearchResponse<Map> traceResponse =
                    elasticsearchClient.search(traceRequest, Map.class);

            return Map.of(
                    "transactionId", transactionId,
                    "traceId", traceId,
                    "events", traceResponse.hits().hits().stream()
                            .map(h -> h.source())
                            .filter(Objects::nonNull)
                            .toList());

        } catch (Exception e) {
            log.error("Trace query failed: txnId={}", transactionId);
            return Map.of("error", "TRACE_UNAVAILABLE");
        }
    }

    /**
     * Active compliance alerts for review.
     */
    public Map<String, Object> getActiveAlerts(
            String severity, int page, int size) {
        // Alerts are in MongoDB — delegate to ComplianceReportRepository
        // or direct MongoDB query
        return Map.of(
                "alerts", List.of(),
                "page", page,
                "size", size,
                "note", "Alerts served from MongoDB compliance_alerts collection");
    }
}