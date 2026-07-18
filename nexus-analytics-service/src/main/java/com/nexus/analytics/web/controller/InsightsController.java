package com.nexus.analytics.web.controller;

import com.nexus.analytics.application.InsightGenerationService;
import com.nexus.analytics.domain.exception.UnauthorizedException;
import com.nexus.analytics.domain.model.FinancialInsight;
import com.nexus.analytics.infrastructure.redis.AnalyticsRedisRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Financial Insights", description = "AI-generated, plain-language insights about the caller's spending — same accountId-not-actually-used caveat as AnalyticsController.")
@SecurityRequirement(name = "X-User-Id")
public class InsightsController {

    private final InsightGenerationService insightService;
    private final AnalyticsRedisRepository redisRepository;

    @Operation(
            summary = "Get AI-generated financial insights for a month",
            description = "Redis-cached for 1 hour — a cache hit is near-instant, a miss calls the " +
                    "LLM and takes noticeably longer. accountId is accepted but not used to scope " +
                    "the query, same as AnalyticsController's endpoints."
    )
    @ApiResponse(responseCode = "200", description = "Insights retrieved (cached or freshly generated)")
    @GetMapping("/accounts/{accountId}/insights/{yearMonth}")
    public ResponseEntity<List<FinancialInsight>> getInsights(
            @Parameter(description = "Accepted but not used to scope the query")
            @PathVariable String accountId,
            @Parameter(description = "Year and month, e.g. 2026-07", required = true)
            @PathVariable String yearMonth,
            @Parameter(description = "Language for the generated insights, defaults to es")
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
        if (userId == null) throw new UnauthorizedException("Authentication required");
        return userId;
    }
}