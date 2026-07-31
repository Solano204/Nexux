package com.nexus.risk.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.core.instrument.config.MeterFilter;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Observability — Risk Scoring Service.
 *
 * @EnableScheduling for NightlyRiskScoringJob (2am cron).
 * Virtual thread executor for parallel tool execution
 * within StructuredTaskScope in RiskScoringAgent.
 *
 * Key Zipkin spans:
 * - risk.agent.compute (full computation)
 * - risk.agent.plan (planning phase)
 * - risk.agent.step.N (each tool call)
 * - risk.agent.synthesis (final profile generation)
 * - risk.tool.* (individual tool observations)
 */
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
    // the caller's ThreadLocal trace context, so StructuredTaskScope tool-execution
    // work dispatched here would otherwise start disconnected root spans instead of
    // children of risk.agent.compute. (BehaviorEventConsumer's own asyncExecutor
    // field is unaffected - it hands a pre-built child Span across threads manually
    // instead of relying on ambient ThreadLocal context.)
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * No error handler existed for this service's @KafkaListener consumers
     * (BehaviorEventConsumer), so they fell back to Spring Kafka's internal
     * default: FixedBackOff(0, 9) - up to 10 same-process retries with ZERO
     * delay on any exception. Spring Boot auto-wires this bean into the
     * auto-configured ConcurrentKafkaListenerContainerFactory automatically.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
    }

    /**
     * Workaround for spring-projects/spring-kafka#4104 — Spring Boot's
     * default Observation-enabled listener container and the legacy
     * MicrometerHolder timer both register a meter named
     * "spring.kafka.listener", and Prometheus rejects whichever registers
     * second. Deny the legacy-shaped meter (identified by its "exception"
     * tag, unique to that convention) so only the Observation-based
     * registration ever claims this meter name. Same fix as
     * identity-service's KafkaConfig — applies here too even without a
     * hand-built ListenerContainerFactory, since this is Spring Boot's own
     * auto-configured Kafka listener hitting the same bug.
     */
    @Bean
    public MeterFilter denyLegacyKafkaListenerMeter() {
        return MeterFilter.deny(id ->
                id.getName().equals("spring.kafka.listener") && id.getTag("exception") != null);
    }

    /**
     * Local throttle for RiskScoringAgent's gpt-4o calls, sized to stay
     * under OpenAI's org-wide TPM limit (seen hitting "Limit 30000, Used
     * 30000" with no local throttling at all). Resilience4j's RateLimiter
     * only bounds request COUNT per period, not token count - OpenAI's
     * limit is token-based, so this is a conservative approximation:
     * nexus.risk.openai-rate-limiter.calls-per-minute defaults to 20,
     * assuming ~1200-1500 tokens/call average across the plan/execute/
     * synthesis calls a single risk computation makes (up to ~15 calls
     * per user - see RiskScoringAgent.MAX_RISK_AGENT_STEPS), which keeps
     * the platform under ~24-30k TPM (80-100% of the limit) even under
     * back-to-back computations. Tune via config, not code, if the actual
     * average token/call differs from this estimate.
     */
    @Bean
    public RateLimiter openAiRateLimiter(
            @Value("${nexus.risk.openai-rate-limiter.calls-per-minute:20}") int callsPerMinute,
            @Value("${nexus.risk.openai-rate-limiter.timeout-seconds:30}") int timeoutSeconds) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(callsPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                .build();
        return RateLimiter.of("openai-risk-scoring", config);
    }
}