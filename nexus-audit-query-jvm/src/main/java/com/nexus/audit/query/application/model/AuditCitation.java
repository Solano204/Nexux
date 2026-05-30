package com.nexus.audit.query.application.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A citation pointing back to a specific audit document retrieved from
 * Elasticsearch / pgvector during the RAG pipeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditCitation {

    /** Elasticsearch document ID or pgvector row ID. */
    private String documentId;

    /** Human-readable title of the audit document. */
    private String title;

    /** The text excerpt that was actually used as context. */
    private String excerpt;

    /** Cosine / dot-product similarity score from the vector search (0–1). */
    private double similarityScore;

    /** Source system that produced the audit record (e.g. "trade-risk-service"). */
    private String sourceSystem;

    /** ISO-8601 date-time string from the original audit document metadata. */
    private String auditDate;

    /** Page or section reference within the document, if applicable. */
    private String sectionReference;
}