package com.nexus.identity.infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session Cache Repository — Redis-backed session list cache.
 *
 * Key: session:{userId}:active
 * TTL: 5 minutes
 * Invalidated on: login, logout, session termination, password change
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "session:";
    private static final String KEY_SUFFIX = ":active";
    private static final Duration TTL = Duration.ofMinutes(5);

    public void cacheActiveSessions(UUID userId, List<Map<String, Object>> sessions) {
        String key = buildKey(userId);
        try {
            String json = objectMapper.writeValueAsString(sessions);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (Exception e) {
            log.warn("Failed to cache sessions for userId={}: {}",
                    userId, e.getMessage());
        }
    }

    public List<Map<String, Object>> getCachedSessions(UUID userId) {
        String key = buildKey(userId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to read session cache for userId={}: {}",
                    userId, e.getMessage());
            return null;
        }
    }

    public void invalidate(UUID userId) {
        redisTemplate.delete(buildKey(userId));
    }

    // Failed login tracking
    public void incrementFailedAttempts(String email) {
        String key = "auth:failed:" + email.toLowerCase();
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            // First failure — set TTL for the window
            redisTemplate.expire(key, Duration.ofMinutes(15));
        }
    }

    public int getFailedAttempts(String email) {
        String key = "auth:failed:" + email.toLowerCase();
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0 : Integer.parseInt(val);
    }

    public void resetFailedAttempts(String email) {
        redisTemplate.delete("auth:failed:" + email.toLowerCase());
    }

    // KYC retry counter
    public int getKycRetryCount(UUID userId) {
        String key = "kyc:retries:" + userId;
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0 : Integer.parseInt(val);
    }

    public void incrementKycRetryCount(UUID userId) {
        String key = "kyc:retries:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofDays(30));
        }
    }

    private String buildKey(UUID userId) {
        return KEY_PREFIX + userId + KEY_SUFFIX;
    }
}