package com.nexus.account.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

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
// @EnableScheduling was missing platform-wide for this service - it did
// not exist anywhere in nexus-account-service (confirmed by grep across
// src/main), which means BalanceSagaParticipant's two @Scheduled methods
// (releaseExpiredReservations - every 5 min, resetDailyLimits - nightly)
// were annotated but Spring never actually registered a scheduler to run
// them. releaseExpiredReservations is the documented safety net for when
// the saga orchestrator fails to send a compensating ReleaseBalanceCommand
// - without it, an expired reservation held a user's funds unavailable
// indefinitely instead of for at most ~24h+5min. resetDailyLimits resetting
// dailyTransactionUsed never running meant a user who hit their daily limit
// once would appear permanently at that limit. See
// CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
@EnableScheduling
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

    /**
     * Platform-wide: adds a consistent error.type tag to every Observation
     * whenever .error(ex) is called - see ErrorTaggingObservationHandler.
     */
    @Bean
    public ObservationHandler<Observation.Context> errorTaggingObservationHandler() {
        return new ErrorTaggingObservationHandler();
    }

    /**
     * Excludes /actuator/** (Docker healthcheck noise). See
     * ActuatorObservationPredicate for why this is an ObservationPredicate,
     * not a SamplerFunction<HttpRequest> (the latter is never consulted by
     * Spring Boot 3.x's HTTP server tracing).
     */
    @Bean
    public ObservationPredicate excludeActuatorObservations() {
        return new ActuatorObservationPredicate(List.of("/actuator"));
    }
}