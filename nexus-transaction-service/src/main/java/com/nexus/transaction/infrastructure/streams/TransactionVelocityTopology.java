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
public class TransactionVelocityTopology {
    private final ObjectMapper objectMapper;
    private static final String INPUT_TOPIC = "transactions.initiated";
    private static final String OUTPUT_TOPIC = "transactions.velocity";
    private static final String STORE_NAME = "velocity-by-user";
    private static final Duration WINDOW_SIZE = Duration.ofMinutes(5);

    @Autowired
    public void buildVelocityTopology(StreamsBuilder builder) {
        JsonSerde<JsonNode> jsonSerde = new JsonSerde<>(objectMapper);
        KStream<String, JsonNode> transactionStream = builder.stream(INPUT_TOPIC, Consumed.with(Serdes.String(), jsonSerde));
        KStream<String, JsonNode> keyedByUser = transactionStream.selectKey((k, v) -> v.path("userId").asText());

        KTable<Windowed<String>, JsonNode> velocityTable = keyedByUser
                .groupByKey(Grouped.with(Serdes.String(), jsonSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW_SIZE))
                .aggregate(
                        () -> { ObjectNode init = objectMapper.createObjectNode(); init.put("count", 0); init.put("totalAmount", "0"); init.put("maxAmount", "0"); init.put("windowStartMs", System.currentTimeMillis()); return init; },
                        (userId, transaction, aggregate) -> {
                            ObjectNode agg = (ObjectNode) aggregate;
                            int count = agg.path("count").asInt() + 1;
                            BigDecimal total = new BigDecimal(agg.path("totalAmount").asText("0")).add(new BigDecimal(transaction.path("amount").asText("0")));
                            BigDecimal incoming = new BigDecimal(transaction.path("amount").asText("0"));
                            BigDecimal max = new BigDecimal(agg.path("maxAmount").asText("0")).max(incoming);
                            agg.put("userId", userId); agg.put("count", count); agg.put("totalAmount", total.toPlainString());
                            agg.put("avgAmount", total.divide(new BigDecimal(count), 4, java.math.RoundingMode.HALF_UP).toPlainString());
                            agg.put("maxAmount", max.toPlainString()); agg.put("windowSizeMinutes", 5);
                            agg.put("computedAt", java.time.Instant.now().toString());
                            return agg;
                        },
                        Materialized.<String, JsonNode>as(Stores.persistentWindowStore(STORE_NAME, Duration.ofMinutes(60), WINDOW_SIZE, false))
                                .withKeySerde(Serdes.String()).withValueSerde(jsonSerde)
                );

        velocityTable.toStream().map((windowedKey, value) -> {
            ObjectNode enriched = (ObjectNode) value;
            enriched.put("userId", windowedKey.key());
            enriched.put("windowStartMs", windowedKey.window().start());
            enriched.put("windowEndMs", windowedKey.window().start() + WINDOW_SIZE.toMillis());
            return new org.apache.kafka.streams.KeyValue<>(windowedKey.key(), (JsonNode) enriched);
        }).to(OUTPUT_TOPIC, Produced.with(Serdes.String(), jsonSerde));
        log.info("Transaction velocity topology built: {} → {} (5-min windows)", INPUT_TOPIC, OUTPUT_TOPIC);
    }
}