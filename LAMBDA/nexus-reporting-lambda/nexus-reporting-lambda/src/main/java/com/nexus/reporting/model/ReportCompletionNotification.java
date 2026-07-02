package com.nexus.reporting.model;

import java.util.List;

public record ReportCompletionNotification(
    String reportDate,
    String status,
    int totalReportTypes,
    int successfulReportTypes,
    int errorCount,
    long durationMs,
    long totalBytes,
    List<String> generatedFiles,
    String generatedAt
) {}
