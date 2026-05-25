package com.nexus.account.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Balance Cache Repository — Redis-backed balance caching.
 *
 * Key: account:balance:{accountId}
 * TTL: 30 seconds (balance changes frequently)
 * Staleness threshold: 60 seconds (after which force DB read)
 *
 * CRITICAL: Cache is NEVER consulted for WRITE operations.
 * Only SELECT reads use the cache. All balance-modifying operations
 * use SELECT FOR UPDATE directly from PostgreSQL.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BalanceCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private static final String BALANCE_KEY_PREFIX = "account:balance:";
    private static final String SUMMARY_KEY_PREFIX = "account:summary:";
    private static final Duration BALANCE_TTL = Duration.ofSeconds(30);
    private static final Duration SUMMARY_TTL = Duration.ofSeconds(60);
    private static final long MAX_STALENESS_SECONDS = 60;

    public void cacheBalance(UUID accountId, BalanceCacheEntry entry) {
        try {
            entry = entry.withCachedAt(Instant.now());
            redisTemplate.opsForValue().set(
                    BALANCE_KEY_PREFIX + accountId,
                    objectMapper.writeValueAsString(entry),
                    BALANCE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache balance for {}: {}",
                    accountId, e.getMessage());
        }
    }

    public BalanceCacheEntry getBalance(UUID accountId) {
        try {
            String json = redisTemplate.opsForValue()
                    .get(BALANCE_KEY_PREFIX + accountId);
            if (json == null) return null;

            BalanceCacheEntry entry = objectMapper.readValue(
                    json, BalanceCacheEntry.class);

            // Staleness check — even if Redis TTL hasn't expired
            if (entry.cachedAt() != null) {
                long ageSeconds = Instant.now()
                        .getEpochSecond() - entry.cachedAt().getEpochSecond();
                if (ageSeconds > MAX_STALENESS_SECONDS) {
                    log.debug("Balance cache stale for {}: {}s old",
                            accountId, ageSeconds);
                    invalidate(accountId);
                    return null; // Force DB read
                }
            }
            return entry;
        } catch (Exception e) {
            log.warn("Failed to read balance cache for {}: {}",
                    accountId, e.getMessage());
            return null;
        }
    }

    public void invalidate(UUID accountId) {
        redisTemplate.delete(BALANCE_KEY_PREFIX + accountId);
    }

    public void invalidateSummary(UUID userId) {
        redisTemplate.delete(SUMMARY_KEY_PREFIX + userId);
    }

    public void cacheSummary(UUID userId, Object summary) {
        try {
            redisTemplate.opsForValue().set(
                    SUMMARY_KEY_PREFIX + userId,
                    objectMapper.writeValueAsString(summary),
                    SUMMARY_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache summary: {}", e.getMessage());
        }
    }

    public record BalanceCacheEntry(
            BigDecimal availableBalance,
            BigDecimal reservedAmount,
            BigDecimal totalBalance,
            String currency,
            String status,
            Instant cachedAt
    ) {
        public BalanceCacheEntry withCachedAt(Instant time) {
            return new BalanceCacheEntry(availableBalance, reservedAmount,
                    totalBalance, currency, status, time);
        }
    }
}