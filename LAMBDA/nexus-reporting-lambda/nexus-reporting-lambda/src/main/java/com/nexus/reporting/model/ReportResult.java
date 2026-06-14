package com.nexus.reporting.model;

import java.util.List;

public record ReportResult(
    String reportType,
    List<String> generatedFiles,
    long totalBytes,
    String status,
    String errorMessage
) {
    public ReportResult(String reportType, List<String> files,
                        long totalBytes, String status) {
        this(reportType, files, totalBytes, status, null);
    }

    public static ReportResult failed(String reportType, String error) {
        return new ReportResult(reportType, List.of(), 0, "FAILED", error);
    }

    public static ReportResult skipped(String reportType, String reason) {
        return new ReportResult(reportType, List.of(), 0, "SKIPPED", reason);
    }
}
