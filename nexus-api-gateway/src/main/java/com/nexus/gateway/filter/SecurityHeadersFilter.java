package com.nexus.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Security Headers Filter — Adds security response headers to every response.
 *
 * Implements OWASP recommended security headers for financial applications.
 * Applied as a global filter — every response gets these headers.
 *
 * Pattern: Decorator Pattern — decorates responses with security metadata
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {
        return chain.filter(exchange).doFinally(signal -> {
            ServerHttpResponse response = exchange.getResponse();

            response.getHeaders().add(
                    "Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains; preload");
            response.getHeaders().add(
                    "X-Content-Type-Options", "nosniff");
            response.getHeaders().add(
                    "X-Frame-Options", "DENY");
            response.getHeaders().add(
                    "Content-Security-Policy",
                    "default-src 'self'; " +
                            "script-src 'self'; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "img-src 'self' data:; " +
                            "connect-src 'self'");
            response.getHeaders().add(
                    "X-XSS-Protection", "1; mode=block");
            response.getHeaders().add(
                    "Referrer-Policy", "no-referrer");
            response.getHeaders().add(
                    "Permissions-Policy",
                    "camera=(), microphone=(), geolocation=()");
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}