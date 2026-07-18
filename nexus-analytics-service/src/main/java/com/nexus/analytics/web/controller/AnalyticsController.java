package com.nexus.analytics.web.controller;

import com.nexus.analytics.application.AnalyticsQueryService;
import com.nexus.analytics.domain.exception.UnauthorizedException;
import com.nexus.analytics.infrastructure.redis.AnalyticsRedisRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Spending analytics for the caller's own user — aggregated across all their accounts, not per-account despite accountId appearing in the URL (see class Javadoc in OpenApiConfig).")
@SecurityRequirement(name = "X-User-Id")
public class AnalyticsController {

    private final AnalyticsQueryService queryService;
    private final AnalyticsRedisRepository redisRepository;

    @Operation(
            summary = "Get monthly spending analytics",
            description = "accountId in the path is accepted but not used to scope the query — " +
                    "results are aggregated across all of the caller's accounts for the given month, " +
                    "regardless of which accountId is passed."
    )
    @ApiResponse(responseCode = "200", description = "Analytics retrieved")
    @GetMapping("/accounts/{accountId}/monthly/{yearMonth}")
    public ResponseEntity<?> getMonthlyAnalytics(
            @Parameter(description = "Accepted but not used to scope the query — see description")
            @PathVariable("accountId") String accountId,
            @Parameter(description = "Year and month, e.g. 2026-07", required = true)
            @PathVariable("yearMonth") String yearMonth,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getMonthlyAnalyticsSafe(
                        userId, YearMonth.parse(yearMonth)));
    }

    @Operation(summary = "Get spending trends", description = "Current month vs previous month comparison — same accountId caveat as getMonthlyAnalytics.")
    @ApiResponse(responseCode = "200", description = "Trends retrieved")
    @GetMapping("/accounts/{accountId}/trends")
    public ResponseEntity<?> getSpendingTrends(
            @Parameter(description = "Accepted but not used to scope the query — see class description")
            @PathVariable("accountId") String accountId,
            HttpServletRequest request) {
        String userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getSpendingTrend(
                        userId, YearMonth.now()));
    }

    @Operation(summary = "Get top merchants this month", description = "Redis-backed, fast — same accountId caveat as getMonthlyAnalytics.")
    @ApiResponse(responseCode = "200", description = "Top merchants retrieved")
    @GetMapping("/accounts/{accountId}/merchants")
    public ResponseEntity<?> getTopMerchants(
            @Parameter(description = "Accepted but not used to scope the query — see class description")
            @PathVariable("accountId") String accountId,
            @Parameter(description = "Max number of merchants to return") @RequestParam(value = "limit", defaultValue = "10") int limit,
            HttpServletRequest request) {
        String userId = extractUserId(request);
        return ResponseEntity.ok(
                redisRepository.getTopMerchants(
                        userId, YearMonth.now(), limit));
    }

    @Operation(summary = "Get platform-wide real-time metrics", description = "Aggregate, not scoped to the caller — same data for every user, mostly useful for a dashboard.")
    @ApiResponse(responseCode = "200", description = "Metrics retrieved")
    @GetMapping("/platform/realtime")
    public ResponseEntity<Map<String, Object>> getPlatformRealtime() {
        return ResponseEntity.ok(
                redisRepository.getPlatformRealtimeMetrics());
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null)
            throw new UnauthorizedException("Authentication required");
        return userId;
    }
}