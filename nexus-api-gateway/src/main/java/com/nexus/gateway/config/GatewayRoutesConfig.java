package com.nexus.gateway.config;

import com.nexus.gateway.filter.JwtAuthenticationFilter;
import com.nexus.gateway.filter.WebhookHmacFilter;
import com.nexus.gateway.ratelimit.UserIdKeyResolver;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory.RequestSizeConfig;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

/**
 * Gateway Routes Configuration — Programmatic route definitions.
 * Route priority: YAML routes and programmatic routes are merged by
 * Spring Cloud Gateway. Order matters — lower order = higher priority.
 *
 * Architecture notes:
 * - All 10 primary routes are defined in application.yml for visibility
 * - This class handles: health check passthrough, actuator internal routes,
 *   and any routes requiring programmatic predicate logic
 * - Never put business routing logic here — keep it in YAML for auditability
 *
 * Route table (programmatic additions):
 *   /health          → gateway local health (no downstream hop)
 *   /api/v1/risk/**  → nexus-risk-scoring-service (authenticated)
 *   /api/v1/kyc/**   → nexus-ai-kyc-service (authenticated)
 */
@Configuration
public class GatewayRoutesConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final WebhookHmacFilter webhookHmacFilter;

    private static final int RATE_LIMITER_REPLENISH_RATE = 10;  // 10 req/s sustained
    private static final int RATE_LIMITER_BURST_CAPACITY = 20;  // 20 req/s burst

    // FIX: The inner config class is RequestSizeConfig, NOT RequestSizeGatewayFilterFactory.Config.
    //      Confirmed from decompiled source: the static inner class is named RequestSizeConfig
    //      and lives at RequestSizeGatewayFilterFactory.RequestSizeConfig.
    private final RequestSizeGatewayFilterFactory requestSizeFilterFactory;

    public GatewayRoutesConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            WebhookHmacFilter webhookHmacFilter,
            RequestSizeGatewayFilterFactory requestSizeFilterFactory) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.webhookHmacFilter = webhookHmacFilter;
        this.requestSizeFilterFactory = requestSizeFilterFactory;
    }

    @Bean
    public RouteLocator programmaticRoutes(RouteLocatorBuilder builder) {

        RedisRateLimiter rateLimiter = new RedisRateLimiter(
                RATE_LIMITER_REPLENISH_RATE,
                RATE_LIMITER_BURST_CAPACITY
        );

        UserIdKeyResolver keyResolver = new UserIdKeyResolver();

        // FIX: Use RequestSizeConfig (the actual inner class name from the decompiled source),
        //      not the previously assumed "Config". The factory's apply() method takes
        //      RequestSizeConfig, calls validate() on it, then returns the GatewayFilter.
        RequestSizeConfig requestSizeConfig = new RequestSizeConfig();
        requestSizeConfig.setMaxSize(DataSize.ofBytes(10 * 1024 * 1024L)); // 10 MB
        GatewayFilter requestSizeFilter = requestSizeFilterFactory.apply(requestSizeConfig);

        return builder.routes()

                // ── Risk Scoring Service ──────────────────────────
                // POST /api/v1/risk/evaluate  — trigger risk evaluation
                // GET  /api/v1/risk/score/**  — fetch cached risk score
                .route("nexus-risk-scoring-service", r -> r
                        .path("/api/v1/risk/**")
                        .filters(f -> f
                                .filter(jwtAuthenticationFilter.apply(
                                        new JwtAuthenticationFilter.Config()))
                                .circuitBreaker(c -> c
                                        .setName("risk-scoring-service")
                                        .setFallbackUri("forward:/fallback/generic"))
                                .requestRateLimiter(rl -> rl
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .retry(retryConfig -> retryConfig
                                        .setRetries(1)
                                        .setStatuses(HttpStatus.SERVICE_UNAVAILABLE))
                                .addRequestHeader("X-Gateway-Version", "1.0.0"))
                        .uri("lb://nexus-risk-scoring-service"))

                // ── AI KYC Service ────────────────────────────────
                // POST /api/v1/kyc/verify     — submit document for verification
                // GET  /api/v1/kyc/status/**  — fetch KYC status
                // Large request size limit for document uploads (10 MB)
                .route("nexus-ai-kyc-service", r -> r
                        .path("/api/v1/kyc/**")
                        .filters(f -> f
                                .filter(jwtAuthenticationFilter.apply(
                                        new JwtAuthenticationFilter.Config()))
                                .circuitBreaker(c -> c
                                        .setName("ai-kyc-service")
                                        .setFallbackUri("forward:/fallback/generic"))
                                .filter(requestSizeFilter)
                                .addRequestHeader("X-Gateway-Version", "1.0.0"))
                        .uri("lb://nexus-ai-kyc-service"))

                // ── Kafka Bridge — Lambda HTTP mode ───────────────
                // POST /internal/v1/bridge/publish?topic=&key=
                // Authenticated by X-Plane-Bridge-Secret header (checked inside
                // the transaction service). No JWT needed — Lambda has no JWT.
                .route("nexus-transaction-bridge", r -> r
                        .path("/internal/v1/bridge/**")
                        .filters(f -> f
                                .circuitBreaker(c -> c
                                        .setName("transaction-bridge")
                                        .setFallbackUri("forward:/fallback/generic")))
                        .uri("lb://nexus-transaction-service"))

                // ── Saga Orchestrator — Internal only ─────────────
                // Only reachable from within Docker network
                .route("nexus-saga-orchestrator-internal", r -> r
                        .path("/internal/v1/saga/**")
                        .and()
                        .remoteAddr("172.20.0.0/16")
                        .filters(f -> f
                                .filter(jwtAuthenticationFilter.apply(
                                        new JwtAuthenticationFilter.Config()))
                                .circuitBreaker(c -> c
                                        .setName("saga-orchestrator")
                                        .setFallbackUri("forward:/fallback/generic")))
                        .uri("lb://nexus-saga-orchestrator"))

                .build();
    }
}