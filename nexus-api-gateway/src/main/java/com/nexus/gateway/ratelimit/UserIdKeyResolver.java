package com.nexus.gateway.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * UserIdKeyResolver — Per-user rate limiting for authenticated routes.
 *
 * Uses X-User-Id header (set by JwtAuthenticationFilter after validation).
 * This creates separate rate limit buckets per user, preventing one user
 * from exhausting limits for others.
 *
 * Used by: all authenticated routes (account, transaction, ledger, analytics, AI)
 */
@Slf4j
@Component("userIdKeyResolver")
public class UserIdKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String userId = exchange.getRequest()
                .getHeaders()
                .getFirst("X-User-Id");

        if (userId == null || userId.isBlank()) {
            // Fallback to IP for unauthenticated requests that reach this resolver
            return Mono.just(getClientIp(exchange.getRequest()));
        }

        return Mono.just("user:" + userId);
    }

    private String getClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null) return forwarded.split(",")[0].trim();
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }
}