package com.nexus.reporting.model;

import java.util.List;

public record ReportingResult(
    String reportDate,
    List<ReportResult> reports,
    List<String> errors,
    long totalDurationMs,
    String status
) {}
