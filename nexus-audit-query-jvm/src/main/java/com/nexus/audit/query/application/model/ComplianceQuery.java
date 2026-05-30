package com.nexus.audit.query.application.model;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class ComplianceQuery {
    private final String queryId;
    private final String naturalLanguageQuery;
    private final String targetUserId;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final QueryType queryType;
}