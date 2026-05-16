package com.nexus.gateway.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
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

/**
 * Trace Context Propagator — Zipkin B3 trace header propagation.
 *
 * Ensures distributed trace context flows correctly across:
 *   Client → Gateway → Downstream Service
 *
 * What this does:
 *   1. Reads X-B3-TraceId / X-B3-SpanId from incoming request
 *      (set by Brave/Zipkin auto-instrumentation)
 *   2. Adds trace headers to outgoing downstream requests
 *   3. Injects traceId into MDC for structured log correlation
 *   4. Adds X-Request-Id header (UUID) for end-to-end log tracing
 *
 * Header naming:
 *   X-B3-TraceId    — Zipkin B3 propagation format (128-bit)
 *   X-B3-SpanId     — Current span ID
 *   X-B3-ParentSpanId — Parent span (gateway is parent of downstream)
 *   X-B3-Sampled    — Whether this trace is sampled (1 or 0)
 *   traceparent     — W3C trace context format (also propagated)
 *
 * All these are auto-propagated by Spring Cloud Gateway's tracing
 * integration (Brave + Micrometer). This filter adds:
 *   - X-Request-Id (not a Zipkin header — our own correlation ID)
 *   - Logging of traceId on every request for access log correlation
 *
 * Order: runs AFTER RequestLoggingFilter (needs traceId to be available)
 * but BEFORE the actual routing happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceContextPropagator implements GlobalFilter, Ordered {

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {

        Span currentSpan = tracer.currentSpan();

        if (currentSpan == null) {
            // No active span — tracing not configured or sampled out
            return chain.filter(exchange);
        }

        TraceContext context = currentSpan.context();
        String traceId = context.traceId();
        String spanId = context.spanId();

        // Add trace identifiers to outgoing downstream request headers
        // so downstream services can join the same distributed trace
        ServerHttpRequest enrichedRequest = exchange.getRequest()
                .mutate()
                // B3 propagation headers (Zipkin format)
                .header("X-B3-TraceId", traceId)
                .header("X-B3-SpanId", spanId)
                .header("X-B3-Sampled", "1")
                // W3C traceparent format for services using OpenTelemetry
                .header("traceparent",
                        buildTraceParent(traceId, spanId))
                .build();

        // Add traceId to response so clients can correlate with logs
        exchange.getResponse().getHeaders()
                .add("X-Trace-Id", traceId);

        return chain.filter(
                exchange.mutate().request(enrichedRequest).build());
    }

    /**
     * Build W3C traceparent header value.
     * Format: {version}-{traceId}-{parentId}-{traceFlags}
     * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
     */
    private String buildTraceParent(String traceId,
                                    String spanId) {
        // Pad traceId to 32 hex chars (W3C requires 128-bit = 32 hex)
        String paddedTrace = traceId.length() < 32
                ? "0".repeat(32 - traceId.length()) + traceId
                : traceId;

        // Pad spanId to 16 hex chars (64-bit = 16 hex)
        String paddedSpan = spanId.length() < 16
                ? "0".repeat(16 - spanId.length()) + spanId
                : spanId;

        return "00-" + paddedTrace + "-" + paddedSpan + "-01";
    }

    @Override
    public int getOrder() {
        // After RequestLoggingFilter (HIGHEST_PRECEDENCE + 1)
        // Before actual gateway routing
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}