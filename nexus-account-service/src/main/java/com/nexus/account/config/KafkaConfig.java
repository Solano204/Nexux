package com.nexus.account.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
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
 * KafkaConfig — Consumer and producer factory configuration.
 *
 * Consumer:
 * - Group: account-service-saga-commands
 * - Manual acknowledgment (ack-mode: MANUAL)
 * - read_committed isolation (only reads committed transactional messages)
 * - Earliest offset reset (process all pending commands on restart)
 * - Concurrency: 3 (matches partition count for saga.commands)
 *
 * Producer:
 * - Idempotent: true (exactly-once producer semantics)
 * - Acks: all (wait for all ISR replicas)
 * - Used for saga.replies topic
 *
 * Error handling:
 * - 3 retry attempts with 1 second backoff, then routed to a Dead Letter
 *   Topic (<original-topic>.dlt) instead of being silently dropped. Piloted
 *   here first per CHANGES-BESTPRACTICES/09_KAFKA_HARDENING_CHANGES.md
 *   Fase 4 - previously this (and 8 other services) used a bare
 *   DefaultErrorHandler with no recoverer, whose default behavior after
 *   exhausting retries is to log and advance the offset, discarding the
 *   message permanently with no DLQ, no alert, no way to replay it.
 *
 * Topics consumed: saga.commands
 * Topics produced: saga.replies, saga.commands.dlt (on exhausted retries)
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:nexus-kafka:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:account-service-saga-commands}")
    private String consumerGroupId;

    // Micrometer's Kafka client metrics (including consumer lag,
    // kafka.consumer.fetch.manager.lag) only get bound when a
    // MicrometerConsumerListener/MicrometerProducerListener is explicitly
    // attached below - Spring Boot only auto-attaches these to consumer/
    // producer factories it builds itself from spring.kafka.* properties,
    // NOT to a hand-built DefaultKafkaConsumerFactory/DefaultKafkaProducerFactory
    // like this class defines. See
    // CHANGES-BESTPRACTICES/09_KAFKA_HARDENING_CHANGES.md Fase 7.
    // @Lazy breaks a real circular dependency between this bean and
    // MeterRegistry's own auto-configuration chain - see identity-service's
    // KafkaConfig for the full stack trace/rationale, same fix applied
    // here since this class follows the same pattern.
    @Autowired
    @Lazy
    private MeterRegistry meterRegistry;

    // ══════════════════════════════════════════════════════════
    // CONSUMER FACTORY
    // ══════════════════════════════════════════════════════════

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10_000);
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    /**
     * Routes a message to "<original-topic>.dlt" (same partition number as
     * the original) once DefaultErrorHandler's retries are exhausted.
     * Spring Kafka stamps standard kafka_dlt-* headers automatically
     * (exception class/message/stacktrace, original topic/partition/
     * offset/timestamp) - no extra code needed to carry that metadata.
     * Depends on the kafkaTemplate bean below - Spring resolves the
     * dependency regardless of declaration order in this class.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterRecoverer(
            KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DeadLetterPublishingRecoverer deadLetterRecoverer) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Manual acknowledgment — message only committed after
        // successful processing (at-least-once delivery)
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        // Observation API and the legacy MicrometerHolder both register a
        // timer named "spring.kafka.listener" with different tag-key sets -
        // Prometheus rejects whichever registers second.
        factory.getContainerProperties().setMicrometerEnabled(false);

        // 3 partitions → 3 concurrent consumers
        factory.setConcurrency(3);

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
     * Zipkin span — ever claims this meter name. Same fix as
     * identity-service's KafkaConfig.
     */
    @Bean
    public MeterFilter denyLegacyKafkaListenerMeter() {
        return MeterFilter.deny(id ->
                id.getName().equals("spring.kafka.listener") && id.getTag("exception") != null);
    }

    // ══════════════════════════════════════════════════════════
    // PRODUCER FACTORY
    // ══════════════════════════════════════════════════════════

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        return template;
    }
}