package com.nexus.account.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.UUID;

/**
 * Reservation Lock Repository — Redis distributed lock.
 *
 * Key: account:reservation-lock:{accountId}
 * TTL: 10 seconds (safety net if process crashes holding lock)
 *
 * Prevents concurrent reservations for the same account
 * at the application layer (complementing PostgreSQL row locks).
 *
 * Lock acquisition: SET ... NX EX (atomic, non-blocking)
 * Lock release: DEL (only if this transaction holds it)
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ReservationLockRepository {

    private final StringRedisTemplate redisTemplate;
    private static final String LOCK_PREFIX = "account:reservation-lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);

    /**
     * Attempts to acquire the distributed lock.
     *
     * @return true if lock acquired, false if another process holds it
     */
    public boolean tryAcquireLock(UUID accountId, String transactionId) {
        String key = LOCK_PREFIX + accountId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, transactionId, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases the lock ONLY if this transaction holds it.
     * Uses Lua script for atomic check-and-delete.
     */
    public void releaseLock(UUID accountId, String transactionId) {
        String key = LOCK_PREFIX + accountId;
        String luaScript = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;
        try {
            redisTemplate.execute(
                    new org.springframework.data.redis.core.script
                            .DefaultRedisScript<>(luaScript, Long.class),
                    java.util.List.of(key),
                    transactionId
            );
        } catch (Exception e) {
            log.warn("Failed to release lock for accountId={}: {}",
                    accountId, e.getMessage());
        }
    }

    public boolean isLocked(UUID accountId) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(LOCK_PREFIX + accountId));
    }
}