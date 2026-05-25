package com.nexus.fraud.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

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
@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }

    /**
     * RestTemplate for querying Transaction Service Kafka Streams
     * interactive query endpoint and internal APIs.
     */
    @Bean
    public RestTemplate transactionServiceClient() {
        return new RestTemplate();
    }

    /**
     * Virtual Thread executor for async Elasticsearch indexing
     * and non-blocking operations.
     */
    @Bean("virtualThreadExecutor")
    public java.util.concurrent.ExecutorService virtualThreadExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }
}