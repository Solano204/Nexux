package com.nexus.notification.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class NotificationRedisRepository {

    private final StringRedisTemplate redisTemplate;

    private static final int MAX_HOURLY_NOTIFICATIONS = 10;
    private static final Duration DEDUP_TTL = Duration.ofSeconds(300);

    // ── Deduplication ─────────────────────────────────────

    public boolean checkAndSetDedup(String userId,
                                    Object eventType,
                                    String eventId) {
        String key = "notification:dedup:" + userId + ":" +
                eventType + ":" + eventId;
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", DEDUP_TTL);
        return Boolean.TRUE.equals(set);
    }

    // ── Rate limiting ─────────────────────────────────────

    public boolean checkRateLimit(String userId) {
        String key = "notification:rate:" + userId + ":hourly";
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            // First notification this hour — set TTL
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        return count <= MAX_HOURLY_NOTIFICATIONS;
    }

    // ── Unread counter ────────────────────────────────────

    public void incrementUnreadCount(String userId) {
        redisTemplate.opsForValue()
                .increment("notification:unread:" + userId);
    }

    public void decrementUnreadCount(String userId) {
        String key = "notification:unread:" + userId;
        Long current = redisTemplate.opsForValue()
                .get(key) != null
                ? Long.parseLong(redisTemplate.opsForValue().get(key))
                : 0L;
        if (current > 0) {
            redisTemplate.opsForValue()
                    .decrement("notification:unread:" + userId);
        }
    }

    public long getUnreadCount(String userId) {
        String val = redisTemplate.opsForValue()
                .get("notification:unread:" + userId);
        return val != null ? Long.parseLong(val) : 0L;
    }

    public void resetUnreadCount(String userId) {
        redisTemplate.opsForValue()
                .set("notification:unread:" + userId, "0");
    }

    // ── Preferences cache ─────────────────────────────────

    public String getCachedPreferences(String userId) {
        return redisTemplate.opsForValue()
                .get("notification:prefs:" + userId);
    }

    public void cachePreferences(String userId, String json) {
        redisTemplate.opsForValue().set(
                "notification:prefs:" + userId, json,
                Duration.ofMinutes(5));
    }

    public void invalidatePreferences(String userId) {
        redisTemplate.delete("notification:prefs:" + userId);
    }
}