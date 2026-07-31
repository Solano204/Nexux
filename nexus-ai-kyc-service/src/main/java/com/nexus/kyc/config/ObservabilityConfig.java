package com.nexus.kyc.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Observability Configuration — AI KYC Service.
 *
 * Virtual thread executor for parallel pre-processing:
 * S3 download + Rekognition analysis run concurrently
 * under StructuredTaskScope.ShutdownOnFailure.
 *
 * Named Zipkin spans per pipeline stage:
 * kyc.verify -> kyc.s3.download, kyc.rekognition.detect,
 * kyc.prescreen.validate, kyc.ai.stage1.extraction,
 * kyc.ai.stage2.verification, kyc.mongodb.persist
 */
// @EnableScheduling did not exist anywhere in nexus-ai-kyc-service. Added
// so OutboxCleanupJob (application.maintenance) fires - see
// CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3/6. No
// pre-existing @Scheduled method was silently broken by its absence; this
// service simply had none until now (SqsRekognitionResultConsumer's
// polling loop uses its own ScheduledExecutorService, unaffected either way).
@Configuration
@EnableAsync
@EnableScheduling
public class ObservabilityConfig {

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

    // ContextExecutorService.wrap: a raw virtual-thread executor doesn't inherit
    // the caller's ThreadLocal trace context, so StructuredTaskScope/CompletableFuture
    // work dispatched here would otherwise start disconnected root spans instead of
    // children of kyc.verify.
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Client-side throttle for Stage1DocumentExtraction/Stage2DataComparison's
     * OpenAI calls - same pattern as nexus-risk-scoring-service's
     * openAiRateLimiter. thread-pool-bulkhead.kyc-verification (above, in
     * application.yml) already bounds CONCURRENCY into the verify()
     * pipeline; this bounds the actual OpenAI call RATE across however
     * many of those concurrent verifications are in flight, which the
     * bulkhead alone doesn't - 10 concurrent verifications each doing 2
     * OpenAI calls can still burst past OpenAI's per-minute limit even
     * with the bulkhead capping "10 at a time". See
     * CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md Fase 2.
     */
    @Bean
    public RateLimiter openAiRateLimiter(
            @Value("${nexus.kyc.openai-rate-limiter.calls-per-minute:20}") int callsPerMinute,
            @Value("${nexus.kyc.openai-rate-limiter.timeout-seconds:30}") int timeoutSeconds) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(callsPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                .build();
        return RateLimiter.of("openai-ai-kyc", config);
    }
}