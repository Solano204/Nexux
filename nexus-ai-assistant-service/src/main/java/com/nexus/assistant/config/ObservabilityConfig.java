package com.nexus.assistant.config;

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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Observability — AI Assistant Service.
 *
 * Virtual thread executor for parallel tool execution
 * via StructuredTaskScope in Plan-then-Act agent.
 *
 * Zipkin spans: ai.chat.process, ai.agent.plan, ai.agent.step.N,
 * ai.tool.get_account_balance, ai.tool.transfer_funds, etc.
 */
@Configuration
@EnableAsync
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
    // children of ai.chat.process/ai.agent.plan.
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Client-side throttle for FinancialAssistantAgent/DocumentAnalysisService's
     * OpenAI calls - same pattern as nexus-risk-scoring-service's
     * openAiRateLimiter (see that class's Javadoc for the real incident
     * that justified it: "Limit 30000, Used 30000" with no local
     * throttling at all). A CircuitBreaker alone only reacts to sustained
     * failures; it does nothing to stop a burst of concurrent chat
     * sessions each making several tool-calling round trips from adding up
     * to an OpenAI 429 in the first place. Only covers the blocking
     * .call() sites (AGENT-mode ReAct loop) - the SIMPLE-mode .stream()
     * paths (primaryClient/fallbackClient) are reactive and would need
     * resilience4j-reactor's RateLimiterOperator, not this Supplier-based
     * RateLimiter; that dependency isn't on this module's classpath today,
     * so those paths are a documented follow-up, not silently covered.
     * See CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md Fase 2.
     */
    @Bean
    public RateLimiter openAiRateLimiter(
            @Value("${nexus.assistant.openai-rate-limiter.calls-per-minute:20}") int callsPerMinute,
            @Value("${nexus.assistant.openai-rate-limiter.timeout-seconds:30}") int timeoutSeconds) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(callsPerMinute)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                .build();
        return RateLimiter.of("openai-ai-assistant", config);
    }
}