package com.nexus.kyc.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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
 * KafkaConfig — Consumer factory configuration.
 *
 * Every other service mixing Spring Cloud Stream with @KafkaListener
 * (account, fraud, identity, ledger, notification, saga-orchestrator,
 * transaction) hand-builds its own kafkaListenerContainerFactory bean with
 * AckMode.MANUAL, rather than relying on Spring Boot's autoconfigured
 * default - Spring Cloud Stream's Kafka binder autoconfiguration doesn't
 * reliably produce a working default @KafkaListener container factory in
 * this combination, and even where it does, its default ack-mode doesn't
 * match KycInitiationConsumer's manual Acknowledgment usage. Without this
 * bean, KycInitiationConsumer's @KafkaListener on "identity.kyc" was never
 * correctly wired, so real KYC document verification never triggered.
 *
 * KycInitiationConsumer previously had no error handler either, so any
 * exception (including a genuinely non-retryable NoSuchKeyException) fell
 * back to Spring Kafka's internal default: FixedBackOff(0, 9) - up to 10
 * same-process retries with ZERO delay, matching the "retried 4+ times in
 * under 2 seconds" symptom. Bounded to 3 retries 2s apart, then routed to
 * a dead-letter topic instead of being retried forever or silently dropped.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:19092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:nexus-ai-kyc-service}")
    private String consumerGroupId;

    // See nexus-account-service's KafkaConfig for the full rationale:
    // Micrometer's Kafka client metrics only auto-attach to consumer/
    // producer factories Spring Boot builds itself, not a hand-built
    // DefaultKafkaConsumerFactory like this one. @Lazy avoids a circular
    // dependency with MeterRegistry's own autoconfiguration chain.
    @Autowired
    @Lazy
    private MeterRegistry meterRegistry;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterRecoverer(
            KafkaTemplate<Object, Object> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition("kyc.initiation.dlq", record.partition()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DeadLetterPublishingRecoverer deadLetterRecoverer) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Manual acknowledgment — KycInitiationConsumer only acks after
        // verificationService.verify() completes successfully.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        // Observation API and the legacy MicrometerHolder both register a
        // timer named "spring.kafka.listener" with different tag-key sets -
        // Prometheus rejects whichever registers second (see
        // denyLegacyKafkaListenerMeter below).
        factory.getContainerProperties().setMicrometerEnabled(false);

        factory.setCommonErrorHandler(new DefaultErrorHandler(
                deadLetterRecoverer, new FixedBackOff(2000L, 3L)));

        return factory;
    }

    /**
     * Workaround for spring-projects/spring-kafka#4104 — Spring Boot's
     * default Observation-enabled listener container and the legacy
     * MicrometerHolder timer both register a meter named
     * "spring.kafka.listener", and Prometheus rejects whichever registers
     * second. Deny the legacy-shaped meter (identified by its "exception"
     * tag, unique to that convention) so only the Observation-based
     * registration ever claims this meter name. Same fix as
     * identity-service's KafkaConfig.
     */
    @Bean
    public MeterFilter denyLegacyKafkaListenerMeter() {
        return MeterFilter.deny(id ->
                id.getName().equals("spring.kafka.listener") && id.getTag("exception") != null);
    }
}
