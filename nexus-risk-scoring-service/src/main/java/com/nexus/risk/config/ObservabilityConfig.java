package com.nexus.risk.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    @Bean("virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}