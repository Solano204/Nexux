package com.nexus.identity.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Observability Configuration — Micrometer + Zipkin for identity service.
 *
 * Key metrics emitted:
 *   identity.registrations{outcome=success|failed}   — registration rate
 *   identity.logins{outcome=success|failed}           — login success/failure
 *   identity.bcrypt.duration (histogram P50/P95/P99)  — BCrypt performance
 *   identity.jwt.issued / identity.jwt.refreshed      — token issuance rate
 *   identity.kyc.initiations                          — KYC initiation rate
 *   identity.redis.blacklist.add                      — revocation rate
 *   identity.ai.kyc.explain                           — AI call rate + latency
 *
 * Cardinality guards:
 *   userId is NEVER a metric dimension — millions of unique IDs would OOM Prometheus.
 *   path tags are masked to /api/v1/auth/{action} (no userId in path).
 */
@Configuration
public class ObservabilityConfig {

    @Value("${spring.application.name:nexus-identity-service}")
    private String applicationName;

    @Value("${ENVIRONMENT:local}")
    private String environment;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(Tags.of(
                        Tag.of("application", applicationName),
                        Tag.of("environment", environment)
                ));
    }

    /**
     * Limit URI tag cardinality — prevent time-series explosion.
     * Identity endpoints are few and well-defined, but defense in depth.
     */
    @Bean
    public MeterFilter uriCardinalityFilter() {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "uri", 100, MeterFilter.deny());
    }

    /**
     * ObservedAspect enables @Observed annotation on Spring beans.
     * JwksCache, JwtIssuer, UserCommandService use this for
     * automatic span + metric creation.
     */
    @Bean
    public ObservedAspect observedAspect(
            ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Platform-wide: adds a consistent error.type tag to every Observation
     * (HTTP, @Observed, or manually-created) whenever .error(ex) is called -
     * see ErrorTaggingObservationHandler for why this wasn't already uniform.
     * Spring Boot's ObservationAutoConfiguration auto-collects every
     * ObservationHandler bean via ObjectProvider and registers it on the
     * ObservationRegistry - no wrapper/customizer bean needed.
     */
    @Bean
    public ObservationHandler<Observation.Context> errorTaggingObservationHandler() {
        return new ErrorTaggingObservationHandler();
    }

    /**
     * Excludes /actuator/** from tracing (pure Docker healthcheck noise -
     * every 10-15s per instance, zero diagnostic value). register/login are
     * already captured since global probability is 1.0 - see
     * ActuatorObservationPredicate for why this is an ObservationPredicate,
     * not a SamplerFunction<HttpRequest> (the latter is never consulted by
     * Spring Boot 3.x's HTTP server tracing).
     */
    @Bean
    public ObservationPredicate excludeActuatorObservations() {
        return new ActuatorObservationPredicate(List.of("/actuator"));
    }
}