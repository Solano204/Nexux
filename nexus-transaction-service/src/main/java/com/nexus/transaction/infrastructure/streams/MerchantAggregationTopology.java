package com.nexus.transaction.infrastructure.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Duration;

@Slf4j @Component @RequiredArgsConstructor
public class MerchantAggregationTopology {
    private final ObjectMapper objectMapper;
    private static final String INPUT_TOPIC = "transactions.completed";
    private static final String OUTPUT_TOPIC = "transactions.merchant-stats";
    private static final String STORE_NAME = "merchant-stats";
    private static final Duration WINDOW_SIZE = Duration.ofHours(1);

    @Autowired
    public void buildMerchantTopology(StreamsBuilder builder) {
        JsonSerde<JsonNode> jsonSerde = new JsonSerde<>(JsonNode.class, objectMapper);
        jsonSerde.ignoreTypeHeaders();
        KStream<String, JsonNode> completedStream = builder.stream(INPUT_TOPIC, Consumed.with(Serdes.String(), jsonSerde));
        KStream<String, JsonNode> withMerchant = completedStream.filter((k, v) -> {
            JsonNode mn = v.path("merchantName");
            return !mn.isMissingNode() && !mn.isNull() && !mn.asText().isBlank();
        });
        KStream<String, JsonNode> keyedByMerchant = withMerchant.selectKey((k, v) -> v.path("merchantName").asText());

        KTable<Windowed<String>, JsonNode> merchantTable = keyedByMerchant
                .groupByKey(Grouped.with(Serdes.String(), jsonSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW_SIZE))
                .aggregate(
                        () -> { ObjectNode init = objectMapper.createObjectNode(); init.put("transactionCount", 0); init.put("totalVolume", "0"); init.put("uniqueUsers", 0); return init; },
                        (merchant, txn, agg) -> {
                            ObjectNode result = (ObjectNode) agg;
                            int count = result.path("transactionCount").asInt() + 1;
                            BigDecimal volume = new BigDecimal(result.path("totalVolume").asText("0")).add(new BigDecimal(txn.path("amount").asText("0")));
                            result.put("merchantName", merchant); result.put("transactionCount", count);
                            result.put("totalVolume", volume.toPlainString());
                            result.put("avgTransaction", volume.divide(new BigDecimal(count), 4, java.math.RoundingMode.HALF_UP).toPlainString());
                            result.put("computedAt", java.time.Instant.now().toString());
                            return result;
                        },
                        Materialized.<String, JsonNode>as(Stores.inMemoryWindowStore(STORE_NAME, Duration.ofHours(24), WINDOW_SIZE, false))
                                .withKeySerde(Serdes.String()).withValueSerde(jsonSerde)
                );

        merchantTable.toStream().map((windowedKey, value) -> new org.apache.kafka.streams.KeyValue<>(windowedKey.key(), value))
                .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), jsonSerde));
    }
}