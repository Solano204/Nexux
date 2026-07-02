package com.nexus.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Observability + async configuration — Notification Service.
 *
 * Key metrics emitted:
 *   notification.rate.limited.total      — requests dropped by rate limiter
 *   notification.dedup.skipped.total     — duplicate events skipped
 *   notification.quiet.hours.deferred.total — notifications deferred
 *   notification.ai.generation.duration  — AI content generation latency
 *   notification.process{eventType}      — per-event-type processing (via @Observed)
 *
 * Cardinality guards:
 *   userId is NEVER a metric dimension.
 *   URI tag capped at 50 — notification endpoints are few.
 *
 * @EnableAsync + @EnableScheduling:
 *   virtualThreadExecutor for parallel channel delivery.
 *   Scheduler for quiet-hours deferred pickup + hourly metric flush.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class ObservabilityConfig {

    @Value("${spring.application.name:nexus-notification-service}")
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
     * Notification endpoints are few, but defense in depth.
     */
    @Bean
    public MeterFilter uriCardinalityFilter() {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "uri", 50, MeterFilter.deny());
    }

    /**
     * ObservedAspect enables @Observed annotation on Spring beans.
     * NotificationProcessingService uses Observation API directly;
     * channel classes can use @Observed for automatic span creation.
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    /**
     * Virtual thread executor for parallel channel delivery.
     * Used by NotificationProcessingService for concurrent data gathering.
     */
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}