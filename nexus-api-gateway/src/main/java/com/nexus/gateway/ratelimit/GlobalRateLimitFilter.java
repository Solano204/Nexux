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
 * Global Rate Limit Filter — Platform-wide DDoS protection.
 *
 * Independent of per-user/per-IP limits.
 * If platform receives more than N requests/second total, reject with 429.
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

    @Value("${nexus.gateway.global-rate-limit.requests-per-second:5000}")
    private int maxRequestsPerSecond;

    @Value("${nexus.gateway.global-rate-limit.enabled:true}")
    private boolean enabled;

    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicReference<Long> windowStart =
            new AtomicReference<>(System.currentTimeMillis());
    private final Counter rejectionCounter;

    public GlobalRateLimitFilter(MeterRegistry meterRegistry) {
        this.rejectionCounter = Counter.builder("gateway.global.rate.limit.rejections")
                .description("Number of requests rejected by global rate limit")
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

        if (count > maxRequestsPerSecond) {
            rejectionCounter.increment();
            log.warn("Global rate limit exceeded: count={} limit={}",
                    count, maxRequestsPerSecond);

            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders()
                    .setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders()
                    .add("Retry-After", "1");

            String body = """
                {
                  "error": "GLOBAL_RATE_LIMIT_EXCEEDED",
                  "message": "Platform is experiencing high traffic. Please retry in 1 second.",
                  "retryAfter": 1
                }
                """;

            DataBuffer buffer = exchange.getResponse()
                    .bufferFactory()
                    .wrap(body.getBytes(StandardCharsets.UTF_8));

            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Right after sanitization but before JWT validation
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}