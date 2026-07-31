package com.nexus.gateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global Rate Limit Filter — Platform-wide DDoS protection, tier-aware
 * (resiliencia guide, Fase 6, punto 3).
 *
 * Independent of per-user/per-IP limits.
 * If platform receives more than N requests/second total, reject with 429.
 *
 * Tier-aware shedding: below RESERVED_FRACTION of the ceiling, every tier
 * is accepted normally. Above it, only Nivel 1/2 (fondos, auth, cuentas)
 * keep getting through — Nivel 3/4 (IA, analítica) sheds first, protecting
 * headroom for the core financial path during a platform-wide surge,
 * instead of every route competing for the same shrinking pool equally.
 *
 * Implements sliding window counter using in-memory AtomicLong.
 * Not shared across gateway instances (each instance has its own counter),
 * but sufficient for single-instance local deployment.
 *
 * In multi-instance production: use Redis INCR with TTL for shared counter.
 */
@Slf4j
@Component
public class GlobalRateLimitFilter implements GlobalFilter, Ordered {

    /** Above this fraction of the ceiling, only Nivel 1/2 traffic passes. */
    private static final double RESERVED_FOR_CORE_FRACTION = 0.80;

    @Value("${nexus.gateway.global-rate-limit.requests-per-second:5000}")
    private int maxRequestsPerSecond;

    @Value("${nexus.gateway.global-rate-limit.enabled:true}")
    private boolean enabled;

    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicReference<Long> windowStart =
            new AtomicReference<>(System.currentTimeMillis());
    private final Counter rejectionCounter;
    private final Counter tieredRejectionCounter;

    public GlobalRateLimitFilter(MeterRegistry meterRegistry) {
        // Same metric name, same tag KEY set ("reason") on both — Prometheus
        // rejects a second registration under one name with a different
        // tag-key set than the first (bit us once already in
        // SagaTimeoutMonitor; not repeating it here).
        this.rejectionCounter = Counter.builder("gateway.global.rate.limit.rejections")
                .tag("reason", "global_ceiling")
                .description("Number of requests rejected by global rate limit")
                .register(meterRegistry);
        this.tieredRejectionCounter = Counter.builder("gateway.global.rate.limit.rejections")
                .tag("reason", "tier_shed_early")
                .description("Nivel 3/4 requests shed early to protect Nivel 1/2 headroom")
                .register(meterRegistry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        long now = System.currentTimeMillis();
        long windowStartTime = windowStart.get();

        // Reset counter if we're in a new 1-second window
        if (now - windowStartTime >= 1000) {
            windowStart.compareAndSet(windowStartTime, now);
            requestCount.set(0);
        }

        long count = requestCount.incrementAndGet();
        long coreReservedThreshold = Math.round(maxRequestsPerSecond * RESERVED_FOR_CORE_FRACTION);

        if (count > maxRequestsPerSecond) {
            rejectionCounter.increment();
            log.warn("Global rate limit exceeded: count={} limit={}",
                    count, maxRequestsPerSecond);
            return reject(exchange, "GLOBAL_RATE_LIMIT_EXCEEDED",
                    "Platform is experiencing high traffic. Please retry in 1 second.", 1);
        }

        if (count > coreReservedThreshold && !isCoreTier(exchange)) {
            tieredRejectionCounter.increment();
            log.warn("Shedding non-core traffic early to protect core capacity: " +
                    "count={} coreThreshold={} path={}", count, coreReservedThreshold,
                    exchange.getRequest().getPath());
            return reject(exchange, "CAPACITY_RESERVED_FOR_CORE_OPERATIONS",
                    "Platform is near capacity — core financial operations are " +
                            "prioritized. Please retry shortly.", 2);
        }

        return chain.filter(exchange);
    }

    /**
     * Nivel 1/2 (Fase 5 SLO): fondos, autenticación, cuentas, ledger — the
     * paths that keep getting served even when Nivel 3/4 (IA, analítica)
     * is already being shed.
     */
    private boolean isCoreTier(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/api/v1/auth")
                || path.startsWith("/api/v1/accounts")
                || path.startsWith("/api/v1/transactions")
                || path.startsWith("/api/v1/ledger")
                || path.startsWith("/api/v1/users")
                || path.startsWith("/internal/v1/users")
                || path.startsWith("/internal/v1/fraud");
    }

    private Mono<Void> reject(ServerWebExchange exchange, String error,
                              String message, int retryAfterSeconds) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders()
                .setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders()
                .add("Retry-After", String.valueOf(retryAfterSeconds));

        String body = """
            {
              "error": "%s",
              "message": "%s",
              "retryAfter": %d
            }
            """.formatted(error, message, retryAfterSeconds);

        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // Right after sanitization but before JWT validation
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}