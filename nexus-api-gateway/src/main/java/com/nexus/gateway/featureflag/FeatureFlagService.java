package com.nexus.gateway.featureflag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Feature Flag Service — Redis-backed (resiliencia guide, Fase 7).
 *
 * Graceful degradation vs. load shedding: load shedding rejects requests
 * REACTIVELY once the server is at capacity. This decides PROACTIVELY what
 * the platform offers at all, independent of whether it has spare
 * capacity — a feature gets turned off because ITS OWN dependency is
 * unhealthy, not because the server is overloaded. Used together: even
 * with plenty of spare gateway capacity, ai-assistant-service can still be
 * disabled here if OpenAI itself is degraded.
 *
 * Same fail-open philosophy as TokenBlacklistService: if Redis itself is
 * unreachable, isEnabled() defaults to true. A broken flag-check must
 * never itself take down the feature it's supposed to gracefully degrade —
 * that would defeat the entire point.
 *
 * Key pattern: feature-flag:{feature} -> "disabled" | (absent = enabled)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private static final String KEY_PREFIX = "feature-flag:";
    private static final String REASON_SUFFIX = ":reason";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public Mono<Boolean> isEnabled(String feature) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + feature)
                .map(value -> !"disabled".equals(value))
                .defaultIfEmpty(true)
                .onErrorResume(e -> {
                    log.warn("Feature flag check failed for '{}', failing open " +
                            "(enabled): {}", feature, e.getMessage());
                    return Mono.just(true);
                });
    }

    public Mono<Void> disable(String feature, String reason) {
        log.warn("Feature '{}' DISABLED: {}", feature, reason);
        // Fase 10: this is the exact "early degradation" signal Fase 10
        // wants alertable — without it, a feature going dark only shows up
        // as a log line no one is tailing.
        Counter.builder("feature.flag.disabled.total")
                .tag("feature", feature)
                .description("Times a feature flag was auto/manually disabled")
                .register(meterRegistry)
                .increment();
        return redisTemplate.opsForValue().set(KEY_PREFIX + feature, "disabled")
                .then(redisTemplate.opsForValue().set(
                        KEY_PREFIX + feature + REASON_SUFFIX,
                        Instant.now() + " — " + reason))
                .then();
    }

    public Mono<Void> enable(String feature) {
        log.info("Feature '{}' manually re-enabled", feature);
        return redisTemplate.delete(KEY_PREFIX + feature)
                .then(redisTemplate.delete(KEY_PREFIX + feature + REASON_SUFFIX))
                .then();
    }

    public Mono<String> disabledReason(String feature) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + feature + REASON_SUFFIX)
                .defaultIfEmpty("unknown");
    }
}
