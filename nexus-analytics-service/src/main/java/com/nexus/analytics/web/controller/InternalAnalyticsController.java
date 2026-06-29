package com.nexus.analytics.web.controller;

import com.nexus.analytics.streams.aggregate.CategorySpendingAggregate;
import com.nexus.analytics.streams.AnalyticsTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.*;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.Map;

/**
 * Internal Analytics Controller — Kafka Streams interactive queries.
 *
 * Serves state store data to other services:
 * - Fraud Service: reads velocity data
 * - Account Service: reads spending patterns for advisor
 * - Risk Scoring Service: reads spending volatility
 *
 * If Kafka Streams is rebalancing, returns 503 with Retry-After.
 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/streams")
@RequiredArgsConstructor
public class InternalAnalyticsController {

    // ANTES: private final KafkaStreams kafkaStreams;
    // AHORA:
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    private KafkaStreams streams() {
        KafkaStreams ks = streamsBuilderFactoryBean.getKafkaStreams();
        if (ks == null) throw new IllegalStateException("Kafka Streams not initialized yet");
        return ks;
    }

    @GetMapping("/category-spending")
    public ResponseEntity<?> getCategorySpending(
            @RequestParam("userId") String userId,
            @RequestParam("category") String category,
            @RequestParam(value = "date", required = false) String date) {

        KafkaStreams ks = streamsBuilderFactoryBean.getKafkaStreams();
        if (ks == null || ks.state() != KafkaStreams.State.RUNNING) {
            return ResponseEntity.status(503)
                    .header("Retry-After", "15")
                    .body(Map.of("status", "STREAMS_NOT_READY",
                            "state", ks == null ? "NOT_INITIALIZED" : ks.state().name()));
        }

        try {
            ReadOnlyWindowStore<String, CategorySpendingAggregate> store =
                    streams().store(StoreQueryParameters.fromNameAndType(
                            AnalyticsTopology.USER_CATEGORY_DAILY_STORE,
                            QueryableStoreTypes.windowStore()));

            String key = userId + "|" + category;
            LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
            Instant start = queryDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = start.plus(Duration.ofDays(1));

            WindowStoreIterator<CategorySpendingAggregate> results =
                    store.fetch(key, start, end);

            return results.hasNext()
                    ? ResponseEntity.ok(results.next().value)
                    : ResponseEntity.ok(CategorySpendingAggregate.zero());

        } catch (InvalidStateStoreException e) {
            log.warn("State store not ready: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of("status", "STORE_NOT_READY"));
        }
    }

    @GetMapping("/health/lag")
    public ResponseEntity<?> getStreamLag() {
        try {
            KafkaStreams ks = streamsBuilderFactoryBean.getKafkaStreams();
            if (ks == null) return ResponseEntity.status(503).body(Map.of("state", "NOT_INITIALIZED"));
            return ResponseEntity.ok(Map.of("state", ks.state().name()));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("state", "ERROR", "message", e.getMessage()));
        }
    }
}