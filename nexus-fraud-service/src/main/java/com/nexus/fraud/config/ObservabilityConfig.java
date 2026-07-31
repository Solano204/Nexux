package com.nexus.fraud.config;

import com.nexus.tracing.observation.ErrorTaggingObservationHandler;
import com.nexus.tracing.sampling.ActuatorObservationPredicate;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Observability Configuration — Fraud Service.
 *
 * Custom observations per tool call become Zipkin spans:
 * - fraud.analysis (root span)
 * - fraud.planning
 * - fraud.tool.velocity_check
 * - fraud.tool.geolocation_anomaly
 * - fraud.tool.merchant_risk
 * - fraud.tool.rag_policy
 * - fraud.tool.behavioral_analysis
 * - fraud.tool.account_relationship
 * - fraud.synthesis
 *
 * Pattern: Section 11 — custom observation per agent tool call
 */
// @EnableScheduling did not exist anywhere in nexus-fraud-service. Unlike
// account-service/transaction-service, no existing @Scheduled method was
// silently broken by its absence - this service simply had none yet.
// Added so the new OutboxCleanupJob (application.maintenance) actually
// fires. See CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
@Configuration
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
     * RestTemplate for querying Transaction Service Kafka Streams
     * interactive query endpoint and internal APIs. Consumed by
     * TransactionServiceClient (infrastructure.http) - named distinctly
     * from that class since Spring's default bean name for a
     * @Component class is its decapitalized simple name, which would
     * otherwise collide with this @Bean method's name.
     *
     * Built from the auto-configured RestTemplateBuilder (not `new
     * RestTemplate()`) so Spring Boot's ObservationRestTemplateCustomizer
     * attaches the client interceptor that injects B3 trace headers and
     * reports a client span - otherwise this call is invisible to Zipkin
     * and starts an unlinked trace on the transaction-service side.
     *
     * Timeouts (resilience guide, Fase 1): this call runs inside the
     * CheckFraudCommand saga step, which has a 60s budget end-to-end
     * (see the Saga guide's Fase 8) — 5s read here leaves that budget
     * room for the rest of the fraud scoring (including the OpenAI call)
     * instead of one slow HTTP call eating the whole window.
     *
     * InternalServiceHeaderInterceptor (added - Fase 3, see
     * CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md): this
     * bean previously sent no X-Internal-Service header at all, while
     * transaction-service's SecurityConfig.InternalServiceAuthFilter
     * requires exactly that header on every /internal/v1/** route it
     * calls (getVelocity, getAccountRelationship). Every real call was
     * getting a 403 in silence - both callers (VelocityCheckTool/
     * AccountRelationshipTool) already catch the failure and substitute a
     * neutral fallback, so fraud scoring was quietly missing this signal
     * with no visible error anywhere.
     */
    @Bean
    public RestTemplate transactionServiceRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .readTimeout(java.time.Duration.ofSeconds(5))
                .additionalInterceptors(
                        new com.nexus.tracing.http.InternalServiceHeaderInterceptor("nexus-fraud-service"))
                .build();
    }

    /**
     * Virtual Thread executor for async Elasticsearch indexing
     * and non-blocking operations.
     *
     * ContextExecutorService.wrap: a raw virtual-thread executor doesn't inherit
     * the caller's ThreadLocal trace context. FraudReActAgent's own tool-execution
     * StructuredTaskScope is fixed separately (see FraudReActAgent.
     * executeToolBatchParallel) since it forks directly rather than going through
     * this bean - kept here too for whatever else picks up "virtualThreadExecutor".
     */
    @Bean("virtualThreadExecutor")
    public java.util.concurrent.ExecutorService virtualThreadExecutor() {
        return ContextExecutorService.wrap(
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }
}