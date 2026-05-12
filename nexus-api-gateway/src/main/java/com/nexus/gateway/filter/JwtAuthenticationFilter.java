package com.nexus.gateway.jwt;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Token Blacklist Service — Redis-backed JWT revocation.
 *
 * Architecture decision: Redis EXISTS check on EVERY authenticated request.
 * Latency: 2-5ms per check (acceptable for financial security).
 *
 * Failure mode: If Redis is unreachable for >100ms, proceeds WITHOUT
 * revocation check (availability over absolute security during outage).
 * This is logged as WARN for monitoring.
 *
 * Redis key pattern: jwt:blacklist:{jti}
 * TTL: set equal to remaining token validity (auto-expires when token expires)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObservationRegistry observationRegistry;

    @Value("${nexus.gateway.jwt.blacklist.redis-key-prefix:jwt:blacklist:}")
    private String keyPrefix;

    @Value("${nexus.gateway.jwt.blacklist.check-timeout-ms:100}")
    private long checkTimeoutMs;

    /**
     * Checks if a JWT is blacklisted (revoked).
     *
     * @param jti JWT ID from the token's jti claim
     * @return Mono<true> if token IS blacklisted (revoked — should be rejected)
     *         Mono<false> if token is NOT blacklisted (valid — proceed)
     */
    public Mono<Boolean> isBlacklisted(String jti) {
        String redisKey = keyPrefix + jti;

        Observation obs = Observation.createNotStarted(
                "gateway.jwt.blacklist.check", observationRegistry).start();

        return redisTemplate.hasKey(redisKey)
                .timeout(Duration.ofMillis(checkTimeoutMs))
                .doOnNext(blacklisted -> {
                    if (blacklisted) {
                        log.warn("Blacklisted token rejected: jti={}", jti);
                        obs.event(Observation.Event.of("blacklist.hit"));
                    } else {
                        obs.event(Observation.Event.of("blacklist.miss"));
                    }
                })
                .onErrorResume(ex -> {
                    // Redis unavailable — log warning and allow request
                    // Security trade-off: availability over revocation during outage
                    log.warn(
                            "REDIS_UNAVAILABLE: JWT revocation check skipped for jti={}. " +
                                    "Error: {}", jti, ex.getMessage());
                    obs.event(Observation.Event.of("blacklist.redis_unavailable"));
                    return Mono.just(false);  // Allow through — do not block users
                })
                .doFinally(signal -> obs.stop());
    }

    /**
     * Adds a JWT to the blacklist.
     * Called by the Identity Service logout flow (not the gateway itself,
     * but exposing this allows admin operations).
     *
     * @param jti JWT ID to blacklist
     * @param ttl How long to keep the blacklist entry (= remaining token validity)
     */
    public Mono<Void> blacklist(String jti, Duration ttl) {
        String redisKey = keyPrefix + jti;
        return redisTemplate.opsForValue()
                .set(redisKey, "1", ttl)
                .then()
                .doOnSuccess(v -> log.info("JWT blacklisted: jti={}", jti))
                .doOnError(e -> log.error("Failed to blacklist JWT: jti={} error={}",
                        jti, e.getMessage()));
    }
}