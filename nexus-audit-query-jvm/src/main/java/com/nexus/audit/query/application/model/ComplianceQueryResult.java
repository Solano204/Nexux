package com.nexus.audit.query.application.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComplianceQueryResult {

    private String queryId;
    private String summary;
    private String overallRisk;
    private List<SuspiciousPattern> patterns;
    private List<AuditEventCitation> keyEvents;
    private List<String> recommendations;
    private boolean sarRecommended;
    private String sarJustification;
    private boolean edsRecommended;
    private List<String> regulatoryReferences;
    private Instant queriedAt;
    private String queriedBy;

    public static ComplianceQueryResult noResults(
            String queryId, String auditorId) {
        return ComplianceQueryResult.builder()
                .queryId(queryId)
                .summary("No audit events found for the specified criteria.")
                .overallRisk("CLEAN")
                .patterns(List.of())
                .keyEvents(List.of())
                .recommendations(List.of("Broaden the date range or adjust filters."))
                .sarRecommended(false)
                .edsRecommended(false)
                .regulatoryReferences(List.of())
                .queriedAt(Instant.now())
                .queriedBy(auditorId)
                .build();
    }

    public static ComplianceQueryResult aiFailure(
            String queryId, String auditorId) {
        return ComplianceQueryResult.builder()
                .queryId(queryId)
                .summary("AI analysis unavailable. Raw event data was retrieved successfully.")
                .overallRisk("UNKNOWN")
                .patterns(List.of())
                .keyEvents(List.of())
                .recommendations(List.of("Review events manually or retry the query."))
                .sarRecommended(false)
                .edsRecommended(false)
                .regulatoryReferences(List.of())
                .queriedAt(Instant.now())
                .queriedBy(auditorId)
                .build();
    }

    public static ComplianceQueryResult error(
            String queryId, String auditorId, String errorMessage) {
        return ComplianceQueryResult.builder()
                .queryId(queryId)
                .summary("Query failed: " + errorMessage)
                .overallRisk("UNKNOWN")
                .patterns(List.of())
                .keyEvents(List.of())
                .recommendations(List.of("Contact support if the issue persists."))
                .sarRecommended(false)
                .edsRecommended(false)
                .regulatoryReferences(List.of())
                .queriedAt(Instant.now())
                .queriedBy(auditorId)
                .build();
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SuspiciousPattern {
        private String patternType;
        private String description;
        private List<String> evidenceEventIds;
        private double riskContribution;
        private String regulatorySignificance;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AuditEventCitation {
        private String eventId;
        private String eventType;
        private Instant timestamp;
        private String sourceService;
        private String summary;
        private String citationReason;
        private String traceId;
    }
}