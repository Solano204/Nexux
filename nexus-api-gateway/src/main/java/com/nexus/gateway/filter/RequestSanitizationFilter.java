package com.nexus.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Request Sanitization Filter — Strips spoofed identity headers.
 *
 * Runs BEFORE any other filter (order = HIGHEST_PRECEDENCE).
 * Prevents clients from injecting X-User-Id or X-User-Roles headers
 * that would be trusted by downstream services.
 *
 * Pattern: Decorator Pattern — wraps each request removing dangerous headers
 *
 * This is the first line of defense against header injection attacks.
 */
@Slf4j
@Component
public class RequestSanitizationFilter implements GlobalFilter, Ordered {

    // Headers that ONLY the gateway is allowed to set
    private static final List<String> PROTECTED_HEADERS = List.of(
            "X-User-Id",
            "X-User-Roles",
            "X-Account-Status",
            "X-Gateway-Internal",
            "X-Request-Id",
            "X-Forwarded-User",
            "X-Auth-User",
            "X-Remote-User"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {
        ServerHttpRequest.Builder requestBuilder =
                exchange.getRequest().mutate();

        boolean headersRemoved = false;
        for (String header : PROTECTED_HEADERS) {
            if (exchange.getRequest().getHeaders().containsKey(header)) {
                requestBuilder.headers(headers -> headers.remove(header));
                log.warn(
                        "Sanitized injected header: header={} remoteAddr={}",
                        header,
                        exchange.getRequest().getRemoteAddress()
                );
                headersRemoved = true;
            }
        }

        if (headersRemoved) {
            return chain.filter(
                    exchange.mutate().request(requestBuilder.build()).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // MUST run first — before JWT filter which sets these headers
        return Ordered.HIGHEST_PRECEDENCE;
    }
}