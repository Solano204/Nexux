package com.nexus.transaction.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.micrometer.context.ContextExecutorService;
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
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// @EnableScheduling was missing platform-wide for this service - it did
// not exist anywhere in nexus-transaction-service (confirmed by grep
// across src/main), which means TransactionSagaOrchestrator's two
// @Scheduled methods (detectAndCompensateStuckTransactions - every 2min,
// detectFraudCheckTimeouts - every 30s) were annotated but Spring never
// actually registered a scheduler to run them. Both are the safety net
// that force-fails and compensates transactions stuck mid-saga - without
// them, a transaction that hangs (e.g. fraud-service down) stays in an
// active, fund-locking state forever instead of timing out. Same silent-
// no-op failure mode found and fixed on nexus-account-service. See
// CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
@Configuration
@EnableAsync
@EnableScheduling
public class ObservabilityConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags("application", "nexus-transaction-service",
                        "service.type", "plane-a");
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

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
     * Platform tag catalog (CHANGES-BESTPRACTICES/03_ZIPKIN_TRACING_CHANGES.md
     * Section 7): tags transactionType/accountId/status on any Observation
     * built with TransactionTaggingObservationHandler.Context. Declared with
     * its own Context subtype (not ObservationHandler<Observation.Context>
     * like errorTaggingObservationHandler above) - Spring Boot's
     * ObservationAutoConfiguration collects ObservationHandler<?> beans via
     * a wildcarded ObjectProvider and lets supportsContext() do the runtime
     * filtering, so this doesn't need (and can't safely use) the wider
     * generic signature.
     */
    @Bean
    public ObservationHandler<TransactionTaggingObservationHandler.Context>
            transactionTaggingObservationHandler() {
        return new TransactionTaggingObservationHandler();
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
     * Virtual Thread executor for async Elasticsearch indexing.
     * Used by @Async("virtualThreadExecutor") in ElasticsearchIndexingService.
     *
     * Wrapped with ContextExecutorService: a raw Executors.newVirtualThreadPerTaskExecutor()
     * does not inherit the caller's ThreadLocal-based Brave trace context - Observation.
     * createNotStarted(...) inside indexAsync() would otherwise always start a brand new
     * root span, disconnected from the transaction-processing trace that triggered it.
     * wrap(ExecutorService) pulls the global ContextRegistry.getInstance() internally,
     * which already knows how to snapshot/restore the trace context because
     * BraveAutoConfiguration registers a ThreadLocalAccessor<TraceContext> into it at
     * startup - no extra wiring needed here beyond the wrap.
     */
    @Bean("virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());
    }
}