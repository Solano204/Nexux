package com.nexus.audit.query.application.model;

import java.time.LocalDate;

public record ComplianceQueryRequest(
        String naturalLanguageQuery,
        String targetUserId,
        LocalDate startDate,
        LocalDate endDate,
        QueryType queryType
) {}