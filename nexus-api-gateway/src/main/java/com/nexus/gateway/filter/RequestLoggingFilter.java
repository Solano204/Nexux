package com.nexus.gateway.filter;


import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Request Logging Filter — Complete access log for security auditing.
 *
 * Logs every request with:
 * - traceId and spanId (for Zipkin correlation)
 * - userId (extracted after JWT validation — null for public routes)
 * - HTTP method, path (masked), status code
 * - Duration in milliseconds
 * - Client IP
 *
 * This is the security audit trail required for financial platforms.
 * Logs ship to Loki via Promtail for long-term retention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {
        Instant start = Instant.now();
        ServerHttpRequest request = exchange.getRequest();

        String traceId = tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : "no-trace";

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = Instant.now().toEpochMilli() -
                    start.toEpochMilli();

            String userId = exchange.getRequest()
                    .getHeaders().getFirst("X-User-Id");
            String requestId = exchange.getRequest()
                    .getHeaders().getFirst("X-Request-Id");
            int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;

            String clientIp = getClientIp(request);

            log.info(
                    "traceId={} requestId={} userId={} method={} path={} " +
                            "status={} durationMs={} clientIp={}",
                    traceId,
                    requestId,
                    maskSensitive(userId),
                    request.getMethod(),
                    maskPath(request.getPath().value()),
                    statusCode,
                    durationMs,
                    clientIp
            );
        });
    }

    private String getClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders()
                .getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private String maskPath(String path) {
        return path.replaceAll(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                "{id}");
    }

    private String maskSensitive(String value) {
        if (value == null) return "anonymous";
        if (value.length() <= 8) return "***";
        return value.substring(0, 4) + "***" +
                value.substring(value.length() - 4);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}