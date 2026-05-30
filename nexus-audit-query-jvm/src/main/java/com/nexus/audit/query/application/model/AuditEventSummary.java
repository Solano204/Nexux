package com.nexus.audit.query.application.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditEventSummary {
    private String eventId;
    private String eventType;
    private String category;
    private String severity;
    private String userId;
    private String resourceType;
    private String resourceId;
    private String sourceService;
    private String traceId;
    private Instant eventTimestamp;
    private Map<String, Object> payload;
    private boolean isFinancialEvent;
    private boolean requiresSarReview;
}