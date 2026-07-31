package com.nexus.audit.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * No error handler existed for this service's @KafkaListener consumer
 * (AuditIndexingConsumer), so it fell back to Spring Kafka's internal
 * default: FixedBackOff(0, 9) - up to 10 same-process retries with ZERO
 * delay on any exception. Spring Boot auto-wires this bean into the
 * auto-configured ConcurrentKafkaListenerContainerFactory automatically.
 */
@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
    }
}
