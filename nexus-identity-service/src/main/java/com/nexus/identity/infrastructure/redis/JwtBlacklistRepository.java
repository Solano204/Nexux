package com.nexus.identity.infrastructure.redis;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

/**
 * JWT Blacklist Repository — Redis-backed token revocation.
 *
 * Key pattern: jwt:blacklist:{jti}
 * TTL: equal to remaining token validity (auto-expires)
 *
 * The API Gateway checks this on every authenticated request.
 * This service adds entries on logout and session termination.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JwtBlacklistRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObservationRegistry observationRegistry;

    private static final String KEY_PREFIX = "jwt:blacklist:";

    /**
     * Adds a JWT to the blacklist.
     *
     * @param jti      JWT ID (jti claim from the token)
     * @param expiresAt When the token naturally expires (TTL matches this)
     */
    public void blacklist(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);

        if (ttl.isNegative() || ttl.isZero()) {
            // Token already expired — no need to blacklist
            log.debug("Skipping blacklist for already-expired token: jti={}",
                    jti);
            return;
        }

        String key = KEY_PREFIX + jti;

        Observation obs = Observation.createNotStarted(
                "identity.redis.blacklist.add", observationRegistry).start();

        try {
            // NX = only set if not exists (idempotent)
            Boolean set = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", ttl);

            if (Boolean.TRUE.equals(set)) {
                log.info("JWT blacklisted: jti={} ttl={}s",
                        jti, ttl.toSeconds());
            } else {
                log.debug("JWT already blacklisted: jti={}", jti);
            }

            obs.event(Observation.Event.of("blacklist.added"));
        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to blacklist JWT: jti={} error={}",
                    jti, e.getMessage());
            // Non-fatal: token will expire naturally
        } finally {
            obs.stop();
        }
    }

    /**
     * Checks if a JWT is blacklisted.
     * Used in AuthController refresh-token endpoint.
     */
    public boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.warn("Redis unavailable for blacklist check: jti={}", jti);
            return false; // Allow through on Redis outage
        }
    }

    /**
     * Publishes revocation to Redis pubsub so the gateway
     * can update its cache immediately.
     */
    public void publishRevocationEvent(String jti) {
        try {
            redisTemplate.convertAndSend("jwt:revoked", jti);
        } catch (Exception e) {
            log.warn("Failed to publish revocation event: jti={}", jti);
        }
    }
}