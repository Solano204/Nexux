package com.nexus.analytics.streams.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.*;

/**
 * Generic JSON Serde for Kafka Streams state stores.
 * Required for all non-primitive aggregate types.
 */
public class JsonSerde<T> implements Serde<T> {

    private final Class<T> type;
    private final ObjectMapper objectMapper;

    public JsonSerde(Class<T> type, ObjectMapper objectMapper) {
        this.type = type;
        this.objectMapper = objectMapper;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            try {
                return data == null ? null
                        : objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Serialization failed", e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, data) -> {
            try {
                return data == null ? null
                        : objectMapper.readValue(data, type);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Deserialization failed for type " +
                                type.getSimpleName(), e);
            }
        };
    }
}