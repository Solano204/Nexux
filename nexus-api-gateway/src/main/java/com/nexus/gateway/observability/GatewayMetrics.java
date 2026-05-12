package com.nexus.gateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gateway Custom Metrics — Domain-specific Micrometer metrics.
 *
 * All gateway-specific metrics beyond the auto-generated Spring Cloud Gateway metrics.
 * These feed the Grafana dashboards for the gateway section.
 *
 * Key metrics:
 * - gateway.auth.failures{reason} — JWT validation failure breakdown
 * - gateway.circuit.breaker.events{service,state} — circuit state transitions
 * - gateway.rate.limit.rejections{keyType} — rate limit hit breakdown
 * - gateway.route.requests{route,status} — request volume per route
 */
@Slf4j
@Component
public class GatewayMetrics {

    // Auth failure counters by reason
    private final Counter missingTokenCounter;
    private final Counter invalidTokenCounter;
    private final Counter expiredTokenCounter;
    private final Counter revokedTokenCounter;
    private final Counter suspendedAccountCounter;

    // Rate limit rejection counters by key type
    private final Counter ipRateLimitCounter;
    private final Counter userRateLimitCounter;
    private final Counter globalRateLimitCounter;

    // JWT blacklist check performance
    private final Timer blacklistCheckTimer;

    // Request size summary
    private final DistributionSummary requestSizeSummary;

    public GatewayMetrics(MeterRegistry registry) {

        missingTokenCounter = Counter.builder("gateway.auth.failures")
                .tag("reason", "missing_token")
                .description("Requests rejected due to missing JWT")
                .register(registry);

        invalidTokenCounter = Counter.builder("gateway.auth.failures")
                .tag("reason", "invalid_token")
                .description("Requests rejected due to invalid JWT signature")
                .register(registry);

        expiredTokenCounter = Counter.builder("gateway.auth.failures")
                .tag("reason", "expired_token")
                .register(registry);

        revokedTokenCounter = Counter.builder("gateway.auth.failures")
                .tag("reason", "revoked_token")
                .register(registry);

        suspendedAccountCounter = Counter.builder("gateway.auth.failures")
                .tag("reason", "suspended_account")
                .register(registry);

        ipRateLimitCounter = Counter.builder("gateway.rate.limit.rejections")
                .tag("keyType", "ip")
                .register(registry);

        userRateLimitCounter = Counter.builder("gateway.rate.limit.rejections")
                .tag("keyType", "user")
                .register(registry);

        globalRateLimitCounter = Counter.builder("gateway.rate.limit.rejections")
                .tag("keyType", "global")
                .register(registry);

        blacklistCheckTimer = Timer.builder("gateway.jwt.blacklist.check.duration")
                .description("Time to check JWT blacklist in Redis")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);

        requestSizeSummary = DistributionSummary.builder("gateway.request.size.bytes")
                .description("Size of incoming request bodies")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);
    }

    public void recordMissingToken() { missingTokenCounter.increment(); }
    public void recordInvalidToken() { invalidTokenCounter.increment(); }
    public void recordExpiredToken() { expiredTokenCounter.increment(); }
    public void recordRevokedToken() { revokedTokenCounter.increment(); }
    public void recordSuspendedAccount() { suspendedAccountCounter.increment(); }
    public void recordIpRateLimit() { ipRateLimitCounter.increment(); }
    public void recordUserRateLimit() { userRateLimitCounter.increment(); }
    public void recordGlobalRateLimit() { globalRateLimitCounter.increment(); }
    public Timer getBlacklistCheckTimer() { return blacklistCheckTimer; }
    public DistributionSummary getRequestSizeSummary() { return requestSizeSummary; }
}