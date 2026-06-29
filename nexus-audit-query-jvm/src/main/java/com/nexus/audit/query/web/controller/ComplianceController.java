package com.nexus.audit.query.web.controller;

import com.nexus.audit.query.application.AuditSearchService;
import com.nexus.audit.query.application.ComplianceQueryService;
import com.nexus.audit.query.application.model.ComplianceQuery;
import com.nexus.audit.query.application.model.ComplianceQueryRequest;
import com.nexus.audit.query.application.model.ComplianceQueryResult;
import com.nexus.audit.query.application.model.QueryType;
import com.nexus.audit.query.infrastructure.mongodb.ComplianceReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Compliance Controller — natural language audit investigation.
 *
 * POST /api/v1/audit/compliance/query
 *   → natural language query → ComplianceQueryResult with citations
 *
 * GET /api/v1/audit/compliance/alerts
 *   → active compliance alerts for review
 *
 * GET /api/v1/audit/users/{userId}/timeline
 *   → chronological audit trail for a user
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class ComplianceController {

    private final ComplianceQueryService queryService;
    private final AuditSearchService searchService;
    private final ComplianceReportRepository reportRepository;

    @PostMapping("/compliance/query")
    public ResponseEntity<ComplianceQueryResult> query(
            @RequestBody ComplianceQueryRequest request,
            HttpServletRequest httpRequest) {

        String auditorId = extractUserId(httpRequest);

        ComplianceQuery query = ComplianceQuery.builder()
                .queryId(UUID.randomUUID().toString())
                .naturalLanguageQuery(request.naturalLanguageQuery())
                .targetUserId(request.targetUserId())
                .startDate(request.startDate() != null
                        ? request.startDate()
                        : LocalDate.now().minusDays(30))
                .endDate(request.endDate() != null
                        ? request.endDate() : LocalDate.now())
                .queryType(request.queryType() != null
                        ? request.queryType()
                        : QueryType.SUSPICIOUS_ACTIVITY)
                .build();

        ComplianceQueryResult result =
                queryService.executeQuery(query, auditorId);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/users/{userId}/timeline")
    public ResponseEntity<?> getUserTimeline(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        return ResponseEntity.ok(
                searchService.getUserTimeline(
                        userId, page, size, startDate, endDate));
    }

    @GetMapping("/compliance/alerts")
    public ResponseEntity<?> getAlerts(
            @RequestParam(defaultValue = "WARNING,CRITICAL")
            String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                searchService.getActiveAlerts(severity, page, size));
    }

    @GetMapping("/compliance/reports")
    public ResponseEntity<?> getReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                reportRepository.findAll(
                        org.springframework.data.domain.PageRequest
                                .of(page, size)));
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new RuntimeException(
                "Authentication required");
        return userId;
    }
}