package com.nexus.audit.query.web.controller;

import com.nexus.audit.query.application.AuditSearchService;
import com.nexus.audit.query.domain.exception.ForbiddenException;
import com.nexus.audit.query.domain.exception.UnauthorizedException;
import com.nexus.audit.query.infrastructure.elasticsearch.AuditElasticsearchRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Audit Controller — non-AI audit queries.
 *
 * GET /api/v1/audit/users/{userId}/events — paginated event list
 * GET /api/v1/audit/transactions/{txnId}/trace — cross-service trace
 * GET /api/v1/audit/platform/statistics — platform-wide stats
 *
 * Compliance-officer/admin only — same role gate as ComplianceController.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "Non-AI audit queries — event timelines and cross-service transaction traces. COMPLIANCE_OFFICER or ADMIN role required on every endpoint.")
@SecurityRequirement(name = "X-User-Id")
@SecurityRequirement(name = "X-User-Roles")
public class AuditController {

    private final AuditSearchService searchService;
    private final AuditElasticsearchRepository auditRepository;

    @Operation(summary = "Get a user's audit event timeline", description = "Paginated, filterable by date range and severity — the full record of what a user did across every service.")
    @ApiResponse(responseCode = "200", description = "Timeline retrieved")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @GetMapping("/users/{userId}/events")
    public ResponseEntity<?> getUserEvents(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size,
            @Parameter(description = "ISO date, filter start") @RequestParam(required = false) String startDate,
            @Parameter(description = "ISO date, filter end") @RequestParam(required = false) String endDate,
            @Parameter(description = "Filter by severity level") @RequestParam(required = false) String severity,
            HttpServletRequest request) {
        requireComplianceRole(request);

        return ResponseEntity.ok(
                searchService.getUserTimeline(
                        userId, page, size, startDate, endDate));
    }

    @Operation(summary = "Get a transaction's cross-service trace", description = "Every audit event across every service that touched this transaction, correlated by trace ID.")
    @ApiResponse(responseCode = "200", description = "Trace retrieved")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @GetMapping("/transactions/{transactionId}/trace")
    public ResponseEntity<?> getTransactionTrace(
            @Parameter(description = "Transaction UUID", required = true)
            @PathVariable String transactionId,
            HttpServletRequest request) {
        requireComplianceRole(request);
        return ResponseEntity.ok(
                searchService.getTransactionTrace(transactionId));
    }

    @Operation(summary = "Get platform-wide audit statistics", description = "Aggregate event count — a health/volume check, not per-user detail.")
    @ApiResponse(responseCode = "200", description = "Stats retrieved")
    @ApiResponse(responseCode = "401", description = "X-User-Id missing")
    @ApiResponse(responseCode = "403", description = "Caller lacks COMPLIANCE_OFFICER/ADMIN role")
    @GetMapping("/platform/statistics")
    public ResponseEntity<Map<String, Object>> getPlatformStats(
            HttpServletRequest request) {
        requireComplianceRole(request);
        long totalEvents = auditRepository.count();
        return ResponseEntity.ok(Map.of(
                "totalAuditEvents", totalEvents,
                "status", "OPERATIONAL"));
    }

    private void requireComplianceRole(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new UnauthorizedException("Authentication required");

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