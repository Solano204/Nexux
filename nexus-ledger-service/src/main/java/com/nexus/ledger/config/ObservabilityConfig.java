package com.nexus.ledger.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Observability + async configuration for the Ledger Service.
 * Virtual thread executor for async MongoDB updates and AI explainer.
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

    /**
     * Wrapped with ContextExecutorService: a raw Executors.newVirtualThreadPerTaskExecutor()
     * does not inherit the caller's ThreadLocal-based Brave trace context, so
     * updateMongoReadModel(...) and SagaFailureExplainerService calls dispatched here would
     * otherwise always start a brand new root span, disconnected from the posting trace
     * that triggered them (see LedgerCommandService.updateMongoReadModel,
     * @TransactionalEventListener(AFTER_COMMIT) + @Async("virtualThreadExecutor")).
     * wrap(ExecutorService) pulls the global ContextRegistry.getInstance() internally,
     * populated by BraveAutoConfiguration's ThreadLocalAccessor<TraceContext> at startup.
     */
    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());
    }
}