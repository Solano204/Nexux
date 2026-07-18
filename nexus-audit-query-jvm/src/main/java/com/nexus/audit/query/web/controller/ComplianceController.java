package com.nexus.audit.query.web.controller;

import com.nexus.audit.query.application.AuditSearchService;
import com.nexus.audit.query.application.ComplianceQueryService;
import com.nexus.audit.query.application.model.ComplianceQuery;
import com.nexus.audit.query.application.model.ComplianceQueryRequest;
import com.nexus.audit.query.application.model.ComplianceQueryResult;
import com.nexus.audit.query.application.model.QueryType;
import com.nexus.audit.query.domain.exception.ForbiddenException;
import com.nexus.audit.query.domain.exception.UnauthorizedException;
import com.nexus.audit.query.infrastructure.mongodb.ComplianceReportRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
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
@Tag(name = "Compliance", description = "Natural-language audit investigation, active alerts, and compliance reports. COMPLIANCE_OFFICER or ADMIN role required on every endpoint — this is the exact gap closed in CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md.")
@SecurityRequirement(name = "X-User-Id")
@SecurityRequirement(name = "X-User-Roles")
public class ComplianceController {

    private final ComplianceQueryService queryService;
    private final AuditSearchService searchService;
    private final ComplianceReportRepository reportRepository;

    @Operation(
            summary = "Run a natural-language compliance query",
            description = "AI-backed investigation against the audit trail, with citations — " +
                    "targetUserId in the body scopes the search to one user (optional). auditorId " +
                    "for the response's own audit trail comes from X-User-Id, not the request body."
    )
    @ApiResponse(responseCode = "200", description = "Query result with citations")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @PostMapping("/compliance/query")
    public ResponseEntity<ComplianceQueryResult> query(
            @RequestBody ComplianceQueryRequest request,
            HttpServletRequest httpRequest) {

        requireComplianceRole(httpRequest);
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

    @Operation(summary = "Get a user's chronological audit timeline", description = "Same underlying data as AuditController's getUserEvents — exposed here too as part of the compliance investigation workflow.")
    @ApiResponse(responseCode = "200", description = "Timeline retrieved")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @GetMapping("/users/{userId}/timeline")
    public ResponseEntity<?> getUserTimeline(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "ISO date, filter start") @RequestParam(required = false) String startDate,
            @Parameter(description = "ISO date, filter end") @RequestParam(required = false) String endDate,
            HttpServletRequest httpRequest) {
        requireComplianceRole(httpRequest);

        return ResponseEntity.ok(
                searchService.getUserTimeline(
                        userId, page, size, startDate, endDate));
    }

    @Operation(summary = "Get active compliance alerts", description = "The compliance officer's work queue — filterable by severity, paginated.")
    @ApiResponse(responseCode = "200", description = "Alerts retrieved (empty page if none)")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @GetMapping("/compliance/alerts")
    public ResponseEntity<?> getAlerts(
            @Parameter(description = "Comma-separated severity levels") @RequestParam(defaultValue = "WARNING,CRITICAL")
            String severity,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        requireComplianceRole(httpRequest);
        return ResponseEntity.ok(
                searchService.getActiveAlerts(severity, page, size));
    }

    @Operation(summary = "List compliance reports", description = "Previously generated compliance reports, paginated.")
    @ApiResponse(responseCode = "200", description = "Reports retrieved (empty page if none)")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @GetMapping("/compliance/reports")
    public ResponseEntity<?> getReports(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        requireComplianceRole(httpRequest);
        return ResponseEntity.ok(
                reportRepository.findAll(
                        org.springframework.data.domain.PageRequest
                                .of(page, size)));
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new UnauthorizedException(
                "Authentication required");
        return userId;
    }

    private void requireComplianceRole(HttpServletRequest request) {
        extractUserId(request);

        String rolesHeader = request.getHeader("X-User-Roles");
        List<String> roles = rolesHeader != null
                ? Arrays.asList(rolesHeader.split(","))
                : List.of();
        if (!roles.contains("COMPLIANCE_OFFICER") && !roles.contains("ADMIN")) {
            throw new ForbiddenException(
                    "COMPLIANCE_OFFICER or ADMIN role required");
        }
    }
}