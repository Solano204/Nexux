package com.nexus.analytics.web.controller;

import com.nexus.analytics.application.InsightGenerationService;
import com.nexus.analytics.domain.model.FinancialInsight;
import com.nexus.analytics.infrastructure.redis.AnalyticsRedisRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/**
 * Insights Controller — AI-generated financial insights.
 *
 * GET /api/v1/analytics/accounts/{accountId}/insights/{yearMonth}
 * Redis cache (1 hour TTL) -> AI generation on cache miss.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class InsightsController {

    private final InsightGenerationService insightService;
    private final AnalyticsRedisRepository redisRepository;

    @GetMapping("/accounts/{accountId}/insights/{yearMonth}")
    public ResponseEntity<List<FinancialInsight>> getInsights(
            @PathVariable String accountId,
            @PathVariable String yearMonth,
            @RequestParam(defaultValue = "es") String language,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        YearMonth period = YearMonth.parse(yearMonth);

        String cacheKey = "analytics:insights:" + userId + ":" + yearMonth;
        List<FinancialInsight> cached = redisRepository.getCachedInsights(cacheKey);
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }

        List<FinancialInsight> insights =
                insightService.generateInsights(userId, period, language);

        redisRepository.cacheInsights(cacheKey, insights);
        return ResponseEntity.ok(insights);
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) throw new RuntimeException("Authentication required");
        return userId;
    }
}