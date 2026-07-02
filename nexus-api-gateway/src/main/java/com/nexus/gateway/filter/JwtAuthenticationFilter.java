package com.nexus.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.gateway.jwt.JwtClaims;
import com.nexus.gateway.jwt.JwtValidator;
import com.nexus.gateway.jwt.TokenBlacklistService;
import com.nexus.gateway.observability.GatewayMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JWT Authentication Filter — Custom GatewayFilterFactory.
 *
 * Applied to all authenticated routes via:
 *   filters:
 *     - name: JwtAuthentication
 * in application.yml route definitions.
 *
 * Processing pipeline (fully non-blocking, Project Reactor chain):
 * ┌─────────────────────────────────────────────────────┐
 * │ 1. Extract Bearer token from Authorization header   │
 * │ 2. Validate RS256 signature via JwtValidator        │
 * │    └─ Rejects: expired, wrong issuer, alg=none,    │
 * │       invalid signature, missing sub/jti            │
 * │ 3. Check Redis JWT blacklist (revocation)           │
 * │    └─ Timeout 100ms → fail-open (allow through)    │
 * │ 4. Check accountStatus claim                        │
 * │    └─ SUSPENDED → 403 ACCOUNT_SUSPENDED            │
 * │ 5. Enrich request headers for downstream services   │
 * │    └─ X-User-Id, X-User-Roles, X-Request-Id        │
 * │       X-Account-Status                              │
 * └─────────────────────────────────────────────────────┘
 *
 * Security guarantees:
 *   - Downstream services NEVER perform JWT validation themselves
 *   - X-User-Id is ONLY set here (after full validation)
 *   - External clients cannot inject X-User-Id
 *     (RequestSanitizationFilter strips it first, runs at
 *     Ordered.HIGHEST_PRECEDENCE before this filter)
 *   - alg=none attacks rejected: JwtValidator checks algorithm before
 *     attempting any signature verification
 *   - HS256 secret-confusion attack rejected: only RS256 accepted
 *
 * Error responses:
 *   401 MISSING_TOKEN         — no Authorization header or empty Bearer
 *   401 INVALID_TOKEN         — bad signature, expired, wrong issuer
 *   401 TOKEN_REVOKED         — jti found in Redis blacklist
 *   403 ACCOUNT_SUSPENDED     — accountStatus claim = SUSPENDED
 *   500 INTERNAL_ERROR        — unexpected failure (rare)
 *
 * All error responses are JSON with fields:
 *   { "error": "CODE", "message": "...", "timestamp": "...", "path": "..." }
 */
@Slf4j
@Component
public class JwtAuthenticationFilter
        extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private final JwtValidator jwtValidator;
    private final TokenBlacklistService blacklistService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final GatewayMetrics gatewayMetrics;  // was changed to non-final

    public JwtAuthenticationFilter(
            JwtValidator jwtValidator,
            TokenBlacklistService blacklistService,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            GatewayMetrics gatewayMetrics) {
        super(Config.class);
        this.jwtValidator = jwtValidator;
        this.blacklistService = blacklistService;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.gatewayMetrics = gatewayMetrics;
    }

    @Override
    public String name() {
        return "JwtAuthentication";
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            String requestId = UUID.randomUUID().toString().replace("-", "");
            String path = exchange.getRequest().getPath().value();
            String method = exchange.getRequest().getMethod().name();

            Observation obs = Observation
                    .createNotStarted("gateway.auth.filter", observationRegistry)
                    .lowCardinalityKeyValue("http.method", method)
                    .lowCardinalityKeyValue("http.path", maskPath(path))
                    .start();

            // ── Step 1: Extract Bearer token ─────────────────────────────
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                obs.event(Observation.Event.of("auth.missing_token"));
                obs.stop();
                gatewayMetrics.recordMissingToken();
                return unauthorized(exchange,
                        "MISSING_TOKEN",
                        "Authorization header with Bearer token is required");
            }

            String token = authHeader.substring(7).trim();
            if (token.isBlank()) {
                obs.event(Observation.Event.of("auth.empty_token"));
                obs.stop();
                gatewayMetrics.recordMissingToken();
                return unauthorized(exchange,
                        "MISSING_TOKEN",
                        "Bearer token must not be empty");
            }

            // ── Steps 2–5: Validate → blacklist check → account check ─────
            return jwtValidator.validate(token)
                    // jwtValidator returns empty Mono on ANY validation failure
                    .switchIfEmpty(Mono.defer(() -> {
                        obs.event(Observation.Event.of("auth.invalid_token"));
                        gatewayMetrics.recordInvalidToken();
                        return Mono.<JwtClaims>error(new AuthException(
                                "INVALID_TOKEN",
                                "Token is invalid, expired, or has a bad signature",
                                HttpStatus.UNAUTHORIZED));
                    }))

                    // ── Step 3: Redis blacklist check ─────────────────────────
                    .flatMap(claims ->
                            blacklistService.isBlacklisted(claims.jti())
                                    .flatMap(blacklisted -> {
                                        if (blacklisted) {
                                            obs.event(
                                                    Observation.Event.of("auth.revoked_token"));
                                            gatewayMetrics.recordRevokedToken();
                                            return Mono.<JwtClaims>error(
                                                    new AuthException(
                                                            "TOKEN_REVOKED",
                                                            "This token has been revoked",
                                                            HttpStatus.UNAUTHORIZED));
                                        }
                                        return Mono.just(claims);
                                    }))

                    // ── Step 4: Account status ────────────────────────────────
                    .flatMap(claims -> {
                        if (claims.isAccountSuspended()) {
                            obs.event(
                                    Observation.Event.of("auth.suspended_account"));
                            gatewayMetrics.recordSuspendedAccount();
                            log.warn("Suspended account access attempt: " +
                                            "userId={} path={}",
                                    maskUserId(claims.userId()),
                                    maskPath(path));
                            return Mono.<JwtClaims>error(new AuthException(
                                    "ACCOUNT_SUSPENDED",
                                    "Your account has been suspended. " +
                                            "Please contact support.",
                                    HttpStatus.FORBIDDEN));
                        }
                        return Mono.just(claims);
                    })

                    // ── Step 5: Enrich downstream request ────────────────────
                    .flatMap(claims -> {
                        String roles = String.join(",", claims.roles());

                        ServerHttpRequest enrichedRequest = exchange.getRequest()
                                .mutate()
                                // Identity headers — set ONLY after full validation
                                // Downstream services trust these without re-validating
                                .header("X-User-Id", claims.userId())
                                .header("X-User-Roles", roles)
                                .header("X-Account-Status",
                                        claims.accountStatus() != null
                                                ? claims.accountStatus() : "")
                                // Correlation ID for distributed logging
                                .header("X-Request-Id", requestId)
                                // Propagate JWT expiry for downstream caching hints
                                .header("X-Token-Expires-At",
                                        claims.expiresAt() != null
                                                ? claims.expiresAt().toString() : "")
                                .build();

                        obs.event(Observation.Event.of("auth.success"));
                        obs.highCardinalityKeyValue("user.id", claims.userId());

                        log.info(
                                "AUTH_OK: requestId={} userId={} method={} " +
                                        "path={} roles={}",
                                requestId,
                                maskUserId(claims.userId()),
                                method,
                                maskPath(path),
                                roles.isEmpty() ? "none" : roles);

                        return chain.filter(
                                exchange.mutate()
                                        .request(enrichedRequest)
                                        .build());
                    })

                    // ── Error handling ────────────────────────────────────────
                    .onErrorResume(AuthException.class, e -> {
                        obs.error(e);
                        obs.stop();
                        return switch (e.getStatus()) {
                            case UNAUTHORIZED -> unauthorized(exchange,
                                    e.getErrorCode(), e.getMessage());
                            case FORBIDDEN -> forbidden(exchange,
                                    e.getErrorCode(), e.getMessage());
                            default -> serverError(exchange, e.getMessage());
                        };
                    })
                    .doFinally(signal -> {
                        if (signal.toString().equals("ON_COMPLETE") ||
                                signal.toString().equals("ON_NEXT")) {
                            obs.stop();
                        }
                    });
        };
    }

    // ── Error response builders ───────────────────────────────────────────

    private Mono<Void> unauthorized(ServerWebExchange exchange,
                                    String code, String message) {
        return writeError(exchange, HttpStatus.UNAUTHORIZED, code, message);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange,
                                 String code, String message) {
        return writeError(exchange, HttpStatus.FORBIDDEN, code, message);
    }

    private Mono<Void> serverError(ServerWebExchange exchange,
                                   String message) {
        return writeError(exchange,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", message);
    }

    private Mono<Void> writeError(ServerWebExchange exchange,
                                  HttpStatus status,
                                  String errorCode,
                                  String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // No WWW-Authenticate on 401 — prevents browser popup in API clients
        Map<String, Object> body = Map.of(
                "error", errorCode,
                "message", message,
                "timestamp", Instant.now().toString(),
                "path", exchange.getRequest().getPath().value()
        );

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            // Fallback: minimal JSON without ObjectMapper
            byte[] fallback = ("{\"error\":\"" + errorCode + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = response.bufferFactory().wrap(fallback);
            return response.writeWith(Mono.just(buffer));
        }
    }

    // ── Path and user masking for logs (PII protection) ──────────────────

    /**
     * Replace UUIDs in paths with {id} for low-cardinality metric labels.
     * /api/v1/accounts/550e8400-e29b-41d4-a716-446655440000
     * → /api/v1/accounts/{id}
     */
    private String maskPath(String path) {
        if (path == null) return "unknown";
        return path.replaceAll(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}" +
                        "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                "{id}");
    }

    /**
     * Partially mask userId for log output.
     * 550e8400-e29b-41d4-a716-446655440000 → 550e***0000
     */
    private String maskUserId(String userId) {
        if (userId == null) return "null";
        if (userId.length() <= 8) return "***";
        return userId.substring(0, 4) + "***" +
                userId.substring(userId.length() - 4);
    }

    // ── Inner types ───────────────────────────────────────────────────────

    /**
     * Configuration class for AbstractGatewayFilterFactory.
     * No fields needed — this filter has no per-route configuration.
     * All settings come from application.yml or constructor injection.
     */
    public static class Config {
        // Intentionally empty.
        // Future: could add per-route requiredRoles or bypassPaths.
    }

    /**
     * Internal exception for clean error handling in the reactive chain.
     * Using a typed exception avoids nested if/else in the Reactor pipeline
     * and makes the onErrorResume handler readable.
     */
    @Getter
    public static class AuthException extends RuntimeException {
        private final String errorCode;
        private final HttpStatus status;

        public AuthException(String errorCode,
                             String message,
                             HttpStatus status) {
            super(message);
            this.errorCode = errorCode;
            this.status = status;
        }
    }
}