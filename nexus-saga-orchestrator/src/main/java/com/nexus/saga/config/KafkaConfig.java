package com.nexus.saga.config;

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
 * Kafka Configuration — Saga Orchestrator.
 *
 * The saga orchestrator is BOTH consumer and producer:
 *   - Consumes: saga.replies, transactions.initiated, users.registered,
 *               identity.verified, identity.rejected
 *   - Produces: saga.commands (commands to downstream services)
 *
 * Consumer config:
 *   isolation.level=read_uncommitted — saga commands are NOT produced
 *     inside DB transactions, so read_committed would cause consumers
 *     to stall indefinitely waiting for a transaction that never commits.
 *   enable.auto.commit=false — manual ACK after processing
 *   auto.offset.reset=earliest — don't miss commands on restart
 *
 * Concurrency=3: saga replies can be processed in parallel since each
 *   saga instance is independent (keyed by sagaId).
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:nexus-kafka:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:saga-orchestrator}")
    private String groupId;

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

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // CRITICAL FIX: use read_uncommitted — saga commands are produced
        // WITHOUT Kafka transactions (producer has idempotence but not
        // transactional.id). read_committed would stall consumers forever.
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
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
     * rationale. Note: saga-orchestrator's listeners span 5 source topics
     * (saga.replies, transactions.initiated, users.registered,
     * identity.verified, identity.rejected) sharing this one factory - the
     * recoverer routes dynamically off record.topic(), so each gets its
     * own correctly-named DLT automatically.
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
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        // Observation API and the legacy MicrometerHolder both register a
        // timer named "spring.kafka.listener" with different tag-key sets -
        // Prometheus rejects whichever registers second.
        factory.getContainerProperties().setMicrometerEnabled(false);
        // Concurrency 3: saga instances are independent, parallel is safe
        factory.setConcurrency(3);
        // Error handler: 3 retries with 1s backoff, then publish to the DLT
        // above instead of silently dropping the message. Paired with the
        // idempotent upsert in TransferSagaProcessor/OnboardingFlowSagaProcessor
        // and the consumer-level fix (rethrow instead of swallow, see
        // IdentityEventConsumer/TransactionEventConsumer/SagaReplyConsumer)
        // that actually lets this handler see failures now.
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

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
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