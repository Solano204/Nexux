package com.nexus.assistant.infrastructure.client;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry-After-aware backoff (resiliencia guide, Fase 6, punto 2).
 *
 * Resilience4j's Retry doesn't read HTTP Retry-After by default — it just
 * runs its own fixed/exponential wait regardless of what the server asked
 * for, which is the opposite of backpressure: retrying faster than the
 * server requested actively works against it while it's trying to
 * recover. This builds a Retry whose wait time honors Retry-After when the
 * failure carries one (from LoadSheddingFilter or GlobalRateLimitFilter,
 * both of which set it), falling back to exponential-with-jitter otherwise.
 *
 * Built as a standalone Retry.of(...) instance rather than pulled from
 * RetryRegistry.retry(name, config): the "ai-assistant-reads" name is
 * already populated from application.yml by Spring Boot's
 * resilience4j-spring-boot3 autoconfiguration before any client
 * constructor runs, and RetryRegistry.retry(name, config) only applies a
 * custom config the FIRST time a name is registered — arriving second, it
 * silently hands back the existing (plain, non-adaptive) instance instead.
 * That's a silent-wrong-behavior trap, not a hard failure, so it's not
 * worth risking here. Trade-off: this instance isn't auto-registered for
 * the /actuator/retries listing the YAML-sourced one gets.
 */
public final class RetryAfterSupport {

    private RetryAfterSupport() {}

    public static Retry buildRetry(String name, int maxAttempts) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalBiFunction(RetryAfterSupport::nextWaitMillis)
                .retryExceptions(java.net.SocketTimeoutException.class,
                        java.net.ConnectException.class,
                        RestClientResponseException.class)
                .build();
        return Retry.of(name, config);
    }

    private static Long nextWaitMillis(Integer attempt, Either<Throwable, Object> result) {
        if (result.isLeft() && result.getLeft() instanceof RestClientResponseException ex) {
            String retryAfter = ex.getResponseHeaders() != null
                    ? ex.getResponseHeaders().getFirst("Retry-After") : null;
            if (retryAfter != null) {
                try {
                    return Duration.ofSeconds(Long.parseLong(retryAfter.trim())).toMillis();
                } catch (NumberFormatException ignored) {
                    // Not a delay-seconds value (could be an HTTP-date) — fall
                    // through to the default backoff rather than guess.
                }
            }
        }
        // Default: exponential backoff + full jitter, same shape as the
        // plain "ai-assistant-reads" YAML config (200ms base, x2, ±50%).
        long base = IntervalFunction.ofExponentialBackoff(
                Duration.ofMillis(200), 2.0).apply(attempt);
        long jitter = ThreadLocalRandom.current().nextLong(
                Math.max(1L, (long) (base * 0.5)));
        return base + jitter;
    }
}
