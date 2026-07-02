package com.nexus.audit.query.web.controller;

import com.nexus.audit.query.application.AuditSearchService;
import com.nexus.audit.query.infrastructure.elasticsearch.AuditElasticsearchRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Audit Controller — non-AI audit queries.
 *
 * GET /api/v1/audit/users/{userId}/events — paginated event list
 * GET /api/v1/audit/transactions/{txnId}/trace — cross-service trace
 * GET /api/v1/audit/platform/statistics — platform-wide stats
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditSearchService searchService;
    private final AuditElasticsearchRepository auditRepository;

    @GetMapping("/users/{userId}/events")
    public ResponseEntity<?> getUserEvents(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String severity) {

        return ResponseEntity.ok(
                searchService.getUserTimeline(
                        userId, page, size, startDate, endDate));
    }

    @GetMapping("/transactions/{transactionId}/trace")
    public ResponseEntity<?> getTransactionTrace(
            @PathVariable String transactionId) {
        return ResponseEntity.ok(
                searchService.getTransactionTrace(transactionId));
    }

    @GetMapping("/platform/statistics")
    public ResponseEntity<Map<String, Object>> getPlatformStats() {
        long totalEvents = auditRepository.count();
        return ResponseEntity.ok(Map.of(
                "totalAuditEvents", totalEvents,
                "status", "OPERATIONAL"));
    }
}