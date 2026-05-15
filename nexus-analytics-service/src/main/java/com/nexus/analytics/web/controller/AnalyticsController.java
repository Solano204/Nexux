package com.nexus.analytics.web.controller;

import com.nexus.analytics.application.AnalyticsQueryService;
import com.nexus.analytics.infrastructure.redis.AnalyticsRedisRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsQueryService queryService;
    private final AnalyticsRedisRepository redisRepository;

    @GetMapping("/accounts/{accountId}/monthly/{yearMonth}")
    public ResponseEntity<?> getMonthlyAnalytics(
            @PathVariable String accountId,
            @PathVariable String yearMonth,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getMonthlyAnalytics(
                        userId, YearMonth.parse(yearMonth)));
    }

    @GetMapping("/accounts/{accountId}/trends")
    public ResponseEntity<?> getSpendingTrends(
            @PathVariable String accountId,
            HttpServletRequest request) {
        String userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getSpendingTrend(
                        userId, YearMonth.now()));
    }

    @GetMapping("/accounts/{accountId}/merchants")
    public ResponseEntity<?> getTopMerchants(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        String userId = extractUserId(request);
        return ResponseEntity.ok(
                redisRepository.getTopMerchants(
                        userId, YearMonth.now(), limit));
    }

    @GetMapping("/platform/realtime")
    public ResponseEntity<Map<String, Object>> getPlatformRealtime() {
        return ResponseEntity.ok(
                redisRepository.getPlatformRealtimeMetrics());
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null)
            throw new RuntimeException("Authentication required");
        return userId;
    }
}