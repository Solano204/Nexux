package com.nexus.kyc.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

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
@Configuration
@EnableAsync
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