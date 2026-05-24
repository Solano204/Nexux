package com.nexus.account.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ObservabilityConfig — Micrometer metrics, tracing, and observations.
 *
 * Three pillars of observability:
 * 1. Metrics → Prometheus (scraped by Grafana)
 * 2. Traces → Zipkin (via Brave bridge)
 * 3. Logs → Loki (via Promtail + logback-spring.xml)
 *
 * Custom metrics registered here or in service constructors:
 * - account.balance.reservation.duration (Timer, p50/p90/p95/p99)
 * - account.balance.lock.wait.duration (Timer)
 * - account.balance.lock.timeout.total (Counter)
 * - account.balance.negative.prevention.total (Counter)
 * - account.balance.reservation.active (Gauge)
 * - account.transaction.limit.violations (Counter)
 * - account.ai.transactions.indexed.total (Counter)
 * - account.ai.transactions.indexing.errors.total (Counter)
 * - account.reservation.expired.released.total (Counter)
 * - account.exceptions.business_rule.total (Counter)
 * - account.exceptions.integrity.total (Counter)
 * - account.exceptions.lock_timeout.total (Counter)
 *
 * All metrics tagged with application=nexus-account-service
 * for Grafana dashboard filtering.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Common tags applied to ALL metrics.
     * Enables Grafana dashboards to filter by service.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(
                        "application", "nexus-account-service",
                        "service.type", "plane-a"
                );
    }

    /**
     * Enables @Timed annotation on methods for automatic timing.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * Enables @Observed annotation for Micrometer Observations.
     * Observations combine metrics + traces in a single API.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}