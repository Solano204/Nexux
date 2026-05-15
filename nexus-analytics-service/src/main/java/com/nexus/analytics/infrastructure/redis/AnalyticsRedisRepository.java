package com.nexus.analytics.infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.domain.model.FinancialInsight;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.*;
import java.util.*;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AnalyticsRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ── Real-time counters ────────────────────────────────────

    public void incrementTransactionCount(Instant timestamp) {
        String minuteKey = "analytics:txn:count:" +
                timestamp.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        redisTemplate.opsForValue().increment(minuteKey);
        redisTemplate.expire(minuteKey, Duration.ofMinutes(5));
    }

    public void incrementTransactionVolume(
            String currency, java.math.BigDecimal amount,
            Instant timestamp) {
        String key = "analytics:txn:volume:" + currency + ":" +
                timestamp.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        redisTemplate.opsForValue()
                .increment(key, amount.longValue());
        redisTemplate.expire(key, Duration.ofMinutes(5));
    }

    // ── Merchant leaderboard (sorted set) ─────────────────────

    public void updateMerchantLeaderboard(
            String userId, String merchantName,
            double amount, YearMonth period) {
        String key = "analytics:merchant:leaderboard:" +
                userId + ":" + period;
        redisTemplate.opsForZSet()
                .incrementScore(key, merchantName, amount);
        // Expire at end of month + 7 days
        LocalDate endOfMonth = period.atEndOfMonth();
        Instant expiry = endOfMonth.plusDays(7)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        redisTemplate.expireAt(key, new Date(expiry.toEpochMilli()));
    }

    public List<Map<String, Object>> getTopMerchants(
            String userId, YearMonth period, int limit) {
        String key = "analytics:merchant:leaderboard:" +
                userId + ":" + period;

        Set<org.springframework.data.redis.core.ZSetOperations
                .TypedTuple<String>> topN = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, 0, limit - 1);

        if (topN == null) return List.of();

        return topN.stream()
                .map(t -> Map.of(
                        "merchant", (Object) t.getValue(),
                        "amount", t.getScore()))
                .toList();
    }

    // ── Insights cache ────────────────────────────────────────

    public List<FinancialInsight> getCachedInsights(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null) return null;
            return objectMapper.readValue(json,
                    new TypeReference<List<FinancialInsight>>() {});
        } catch (Exception e) {
            log.warn("Cache read failed: {}", e.getMessage());
            return null;
        }
    }

    public void cacheInsights(String cacheKey,
                              List<FinancialInsight> insights) {
        try {
            String json = objectMapper.writeValueAsString(insights);
            redisTemplate.opsForValue().set(
                    cacheKey, json, Duration.ofHours(1));
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.getMessage());
        }
    }

    // ── Platform real-time metrics ────────────────────────────

    public Map<String, Object> getPlatformRealtimeMetrics() {
        Instant now = Instant.now();
        Map<String, Object> metrics = new LinkedHashMap<>();

        List<Long> txnsPerMinute = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant minute = now.minus(
                            Duration.ofMinutes(i))
                    .truncatedTo(
                            java.time.temporal.ChronoUnit.MINUTES);
            String key = "analytics:txn:count:" + minute;
            String val = redisTemplate.opsForValue().get(key);
            txnsPerMinute.add(val != null
                    ? Long.parseLong(val) : 0L);
        }

        metrics.put("transactionsPerMinuteLast10", txnsPerMinute);
        metrics.put("timestamp", now.toString());
        return metrics;
    }
}