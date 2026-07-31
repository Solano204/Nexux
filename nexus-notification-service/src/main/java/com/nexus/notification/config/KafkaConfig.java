package com.nexus.notification.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Configuration — Notification Service.
 *
 * Multi-topic consumer — subscribes to more topics than any other service:
 * transactions.completed, transactions.failed, fraud.flagged,
 * accounts.created, users.registered, identity.verified/rejected, saga.commands
 *
 * max.poll.records=100: higher than other services — notification processing is fast.
 * Manual ack: offset committed only after MongoDB persistence succeeds.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // Micrometer's Kafka client metrics (including consumer lag,
    // kafka.consumer.fetch.manager.lag) only get bound when a
    // MicrometerConsumerListener is explicitly attached below - Spring
    // Boot only auto-attaches these to consumer factories it builds
    // itself from spring.kafka.* properties, NOT to a hand-built
    // DefaultKafkaConsumerFactory like this class defines (the producer
    // side of this service has no such gap - it relies on Spring Boot's
    // autoconfigured KafkaTemplate, which already gets this). See
    // CHANGES-BESTPRACTICES/09_KAFKA_HARDENING_CHANGES.md Fase 7.
    @Autowired
    private MeterRegistry meterRegistry;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    /**
     * Routes a message to "<original-topic>.dlt" (same partition number as
     * the original) once DefaultErrorHandler's retries are exhausted. See
     * account-service's KafkaConfig (Fase 4 pilot) and
     * CHANGES-BESTPRACTICES/09_KAFKA_HARDENING_CHANGES.md for the full
     * rationale. Note: this service's listeners span 8 source topics
     * sharing this one factory - the recoverer routes dynamically off
     * record.topic(), so each gets its own correctly-named DLT
     * automatically.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterRecoverer(
            KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    kafkaListenerContainerFactory(DeadLetterPublishingRecoverer deadLetterRecoverer) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setConcurrency(3);
        // Error handler: 3 retries with 1s backoff, then publish to the DLT
        // above instead of silently dropping the message.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                deadLetterRecoverer, new FixedBackOff(1000L, 3L)));
        return factory;
    }
}