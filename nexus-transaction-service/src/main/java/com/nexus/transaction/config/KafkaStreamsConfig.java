package com.nexus.transaction.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultProperties;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaStreamsConfig — Embedded stream processing configuration.
 *
 * Topologies:
 * 1. TransactionVelocityTopology — 5-min user velocity windows
 * 2. MerchantAggregationTopology — 1-hour merchant stats
 *
 * State stores backed by RocksDB, changelog replicated to Kafka.
 * Interactive queries available for fraud service velocity lookups.
 *
 * application.id: nexus-transaction-streams
 * num.stream.threads: 4
 * state.dir: /tmp/kafka-streams-state (Docker volume mount)
 * processing.guarantee: exactly_once_v2
 */
@Configuration
@EnableKafkaStreams
public class KafkaStreamsConfig {

    @Value("${spring.kafka.streams.bootstrap-servers:${spring.kafka.bootstrap-servers:nexus-kafka:9092}}")
    private String bootstrapServers;

    @Value("${spring.kafka.streams.application-id:nexus-transaction-streams}")
    private String applicationId;

    @Value("${spring.kafka.streams.properties.state.dir:/tmp/kafka-streams-state}")
    private String stateDir;

    @Bean(name = KafkaStreamsDefaultProperties.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                Serdes.StringSerde.class);
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
                StreamsConfig.EXACTLY_ONCE_V2);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 4);
        // RocksDB cache: 10MB for hot keys
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 10_485_760L);
        // Replication factor for changelog topics
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);
        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public JsonSerde<JsonNode> jsonNodeSerde(ObjectMapper objectMapper) {
        return new JsonSerde<>(objectMapper);
    }
}