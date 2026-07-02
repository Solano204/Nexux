package com.nexus.audit.query.application.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "compliance_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceReport {
    @Id
    private String reportId;
    private String queryId;
    private String auditorUserId;
    private Object query;
    private Object result;
    private int eventsAnalyzed;
    private Instant generatedAt;
    private String reportStatus;
    private String aiModelUsed;
}