package com.nexus.identity.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Configuration — SAGA command consumer.
 *
 * The identity service is a KAFKA CONSUMER only for saga.commands.
 * It does NOT produce directly to Kafka — all events go via the Outbox
 * (Debezium reads PostgreSQL WAL → publishes to Kafka).
 *
 * Consumer config:
 *   isolation.level=read_committed — only read committed messages
 *     (prevents reading messages from rolled-back transactions)
 *   enable.auto.commit=false — manual ACK after processing
 *   auto.offset.reset=earliest — don't miss commands on restart
 *
 * SagaCommandConsumer uses MANUAL ack mode:
 *   - Message is re-delivered if Lambda doesn't call ack.acknowledge()
 *   - DLQ configured at broker level after maxRetry failures
 */
@Configuration
@EnableKafka
@Slf4j

public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:nexus-kafka:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:identity-service-saga-commands}")
    private String groupId;

    // Micrometer's Kafka client metrics (including consumer lag,
    // kafka.consumer.fetch.manager.lag) only get bound when a
    // MicrometerConsumerListener/MicrometerProducerListener is explicitly
    // attached below - Spring Boot only auto-attaches these to consumer/
    // producer factories it builds itself from spring.kafka.* properties,
    // NOT to a hand-built DefaultKafkaConsumerFactory/DefaultKafkaProducerFactory
    // like this class defines. Without this, /actuator/prometheus never had
    // Kafka client metrics for this service despite exposing the endpoint -
    // see CHANGES-BESTPRACTICES/09_KAFKA_HARDENING_CHANGES.md Fase 7.
    // @Lazy breaks a real circular dependency: this class needs MeterRegistry
    // to manually attach MicrometerConsumerListener/MicrometerProducerListener
    // (Spring Boot only auto-attaches those to factories it builds itself,
    // not to these hand-built ones - see the comment on this field's
    // original addition below), but MeterRegistry's own auto-configuration
    // chain (webMvcObservationFilter -> observationRegistry ->
    // tracingAwareMeterObservationHandler -> prometheusMeterRegistry) ends up
    // needing this bean back, deadlocking bean creation with
    // "Requested bean is currently in creation" if injected eagerly. Confirmed
    // live via docker logs - APPLICATION FAILED TO START, not a WARNING.
    // @Lazy injects a proxy instead, deferring actual resolution until the
    // consumerFactory()/producerFactory() @Bean methods below actually call
    // a method on it, by which point the context has finished forming.
    @Autowired
    @Lazy
    private MeterRegistry meterRegistry;

    @PostConstruct
    public void logConfig() {
        log.info("=== KafkaConfig bootstrap-servers resolved: [{}] ===", bootstrapServers);
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Only consume messages from committed transactions
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // Reasonable fetch limits
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    /**
     * Routes a message to "<original-topic>.dlt" (same partition number as
     * the original) once DefaultErrorHandler's retries are exhausted. See
     * account-service's KafkaConfig (Fase 4 pilot) and
     * CHANGES-BESTPRACTICES/09_KAFKA_HARDENING_CHANGES.md for the full
     * rationale.
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

        var factory =
                new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory());
        // MANUAL ACK — SagaCommandConsumer calls ack.acknowledge() explicitly
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        // Observation API and the legacy MicrometerHolder both register a
        // timer named "spring.kafka.listener" with different tag-key sets -
        // Prometheus rejects whichever registers second.
        factory.getContainerProperties().setMicrometerEnabled(false);
        // Concurrency 1: SAGA commands are ordered per userId,
        // parallel consumption would break ordering
        factory.setConcurrency(1);
        // Error handler: 3 retries with 1s backoff, then publish to the DLT
        // above instead of silently dropping the message.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                deadLetterRecoverer, new FixedBackOff(1000L, 3L)));
        return factory;
    }

    /**
     * Workaround for spring-projects/spring-kafka#4104 — setMicrometerEnabled(false)
     * above does not actually stop the legacy MicrometerHolder timer from
     * registering under the same name as the Observation-based one, so
     * Prometheus rejects whichever registers second. Deny the legacy-shaped
     * meter (identified by its "exception" tag, unique to that convention)
     * so only the Observation-based registration — the one also driving the
     * Zipkin span — ever claims this meter name.
     */
    @Bean
    public MeterFilter denyLegacyKafkaListenerMeter() {
        return MeterFilter.deny(id ->
                id.getName().equals("spring.kafka.listener") && id.getTag("exception") != null);
    }

    /**
     * ProducerFactory is included for potential future use
     * (e.g., DLQ republish) but identity service primarily uses Outbox.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        // Exactly-once semantics: enable idempotent producer
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory());
        template.setObservationEnabled(true);
        return template;
    }
}