package com.nexus.identity.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

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
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:nexus-kafka:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:identity-service-saga-commands}")
    private String groupId;

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
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    kafkaListenerContainerFactory() {

        var factory =
                new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory());
        // MANUAL ACK — SagaCommandConsumer calls ack.acknowledge() explicitly
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL);
        // Concurrency 1: SAGA commands are ordered per userId,
        // parallel consumption would break ordering
        factory.setConcurrency(1);
        return factory;
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
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}