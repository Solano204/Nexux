package com.nexus.assistant.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * Session State Repository — Redis session management for AI assistant.
 *
 * Keys:
 * - ai:session:{conversationId} — current mode, pending plan, recent entities (TTL 30min)
 * - ai:rate:{userId}:tokens:hour — token usage rate limit (TTL 1h)
 * - ai:rate:{userId}:messages:day — message count rate limit (TTL midnight)
 * - ai:tool-cache:{userId}:{toolName}:{argsHash} — tool result cache (TTL 30s)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionStateRepository {

    private final StringRedisTemplate redisTemplate;

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration TOOL_CACHE_TTL = Duration.ofSeconds(30);
    private static final int MAX_MESSAGES_PER_HOUR = 30;

    public void saveSessionState(String conversationId, String stateJson) {
        redisTemplate.opsForValue().set(
                "ai:session:" + conversationId, stateJson, SESSION_TTL);
    }

    public String getSessionState(String conversationId) {
        return redisTemplate.opsForValue().get(
                "ai:session:" + conversationId);
    }

    public void deleteSession(String conversationId) {
        redisTemplate.delete("ai:session:" + conversationId);
    }

    public boolean checkRateLimit(String userId) {
        String key = "ai:rate:" + userId + ":messages:hour";
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        return count != null && count <= MAX_MESSAGES_PER_HOUR;
    }

    public void cacheToolResult(String userId, String toolName,
                                String argsHash, String result) {
        String key = "ai:tool-cache:" + userId + ":" + toolName + ":" + argsHash;
        redisTemplate.opsForValue().set(key, result, TOOL_CACHE_TTL);
    }

    public String getCachedToolResult(String userId, String toolName,
                                      String argsHash) {
        return redisTemplate.opsForValue().get(
                "ai:tool-cache:" + userId + ":" + toolName + ":" + argsHash);
    }
}