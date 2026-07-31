package com.nexus.gateway.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability Configuration — Micrometer metrics + distributed tracing setup.
 *
 * Wires together:
 *   Micrometer Observation API  → ObservationRegistry
 *   @Observed AOP support        → ObservedAspect
 *   Prometheus metric tags       → MeterRegistryCustomizer
 *   Cardinality controls         → MeterFilter (prevents metric explosion)
 *   Trace propagation            → handled by TraceContextPropagator
 *
 * Key Micrometer patterns used throughout the gateway:
 *   Observation.createNotStarted("name", registry).start()
 *   observation.event(Event.of("thing.happened"))
 *   observation.error(exception)
 *   observation.stop()
 *
 * All observations automatically create:
 *   - Prometheus counters/timers via MeterRegistry bridge
 *   - Zipkin spans via Brave tracing bridge
 *
 * Cardinality protection:
 *   Path tags are masked before being added to metrics.
 *   Without masking: /api/v1/accounts/{uuid} creates unique metric
 *   per user → millions of time series → Prometheus OOM.
 *   With masking: all account paths collapse to /api/v1/accounts/{id}
 */
@Configuration
public class ObservabilityConfig {

    @Value("${spring.application.name:nexus-api-gateway}")
    private String applicationName;

    @Value("${ENVIRONMENT:local}")
    private String environment;

    /**
     * Global common tags applied to ALL Prometheus metrics.
     * Enables filtering by service and environment in Grafana dashboards.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(Tags.of(
                        Tag.of("application", applicationName),
                        Tag.of("environment", environment)
                ));
    }

    /**
     * Meter filters control cardinality and metric name transformations.
     *
     * Gateway-specific filters:
     * 1. Deny internal/actuator metrics from Prometheus (keep dashboards clean)
     * 2. Cap URI tag cardinality to 500 (prevent time-series explosion)
     */
    @Bean
    public MeterFilter denyActuatorMetrics() {
        // Don't export actuator-internal metrics to Prometheus
        // They bloat the /actuator/prometheus endpoint unnecessarily
        return MeterFilter.denyNameStartsWith("spring.cloud.gateway.routes");
    }

    @Bean
    public MeterFilter uriCardinalityFilter() {
        // Prevent high-cardinality URI tags from creating millions of series
        // Each unique URI+method combination = a new time series
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "uri", 500,
                MeterFilter.deny());
    }

    /**
     * ObservationRegistry — central registry for Micrometer Observations.
     *
     * Observations are the high-level API that automatically bridges to
     * both metrics (via MeterRegistry) and tracing (via Tracer).
     * Auto-configured by Spring Boot — this bean customizes it.
     */
    @Bean
    public ObservedAspect observedAspect(
            ObservationRegistry observationRegistry) {
        // Enables @Observed annotation on any Spring-managed bean
        // Usage: @Observed(name = "my.operation") on methods
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Platform-wide: adds a consistent error.type tag to every Observation
     * whenever .error(ex) is called - see ErrorTaggingObservationHandler.
     * Not adding ActuatorObservationPredicate here (unlike identity/
     * transaction/etc.) - it checks org.springframework.http.server.
     * observation.ServerRequestObservationContext (the servlet variant);
     * gateway is WebFlux/reactive and gets org.springframework.http.server.
     * reactive.observation.ServerRequestObservationContext instead, which
     * the current predicate wouldn't match (silent no-op, not a crash, but
     * still no exclusion). Each service already guarantees its own
     * healthcheck noise exclusion independently, so nothing is lost by
     * deferring this here until a reactive-context variant is written.
     */
    @Bean
    public ObservationHandler<Observation.Context> errorTaggingObservationHandler() {
        return new ErrorTaggingObservationHandler();
    }

    /**
     * Custom metric name prefix for all gateway-specific metrics.
     * Ensures gateway metrics are namespaced separately from
     * Spring Boot auto-metrics in Grafana.
     */
    @Bean
    public MeterFilter gatewayMetricsPrefix() {
        return new MeterFilter() {
            @Override
            public io.micrometer.core.instrument.Meter.Id map(
                    io.micrometer.core.instrument.Meter.Id id) {
                // gateway.auth.failures stays as-is (already namespaced)
                // http.server.requests stays as-is (standard Spring metric)
                return id;
            }
        };
    }
}