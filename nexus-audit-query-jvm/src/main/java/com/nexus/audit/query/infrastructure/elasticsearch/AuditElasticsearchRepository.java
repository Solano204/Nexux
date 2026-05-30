package com.nexus.audit.query.infrastructure.elasticsearch;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Audit event document for Spring Data Elasticsearch reads.
 * Write path uses Quarkus native — this is read-only.
 */


@Repository
public interface AuditElasticsearchRepository
        extends ElasticsearchRepository<AuditEventDocument, String> {

    List<AuditEventDocument> findByUserIdOrderByEventTimestampDesc(
            String userId);

    List<AuditEventDocument> findByUserIdAndSeverityOrderByEventTimestampDesc(
            String userId, String severity);

    List<AuditEventDocument> findByTraceIdOrderByEventTimestampAsc(
            String traceId);

    long countByUserIdAndCategory(String userId, String category);
}