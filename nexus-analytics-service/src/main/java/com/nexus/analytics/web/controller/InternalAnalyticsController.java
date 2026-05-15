package com.nexus.analytics.web.controller;

import com.nexus.analytics.streams.aggregate.CategorySpendingAggregate;
import com.nexus.analytics.streams.AnalyticsTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.*;
import org.springframework.http.ResponseEntity;
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

    private final KafkaStreams kafkaStreams;

    @GetMapping("/category-spending")
    public ResponseEntity<?> getCategorySpending(
            @RequestParam String userId,
            @RequestParam String category,
            @RequestParam(required = false) String date) {

        if (kafkaStreams.state() ==
                KafkaStreams.State.REBALANCING) {
            return ResponseEntity.status(503)
                    .header("Retry-After", "15")
                    .body(Map.of(
                            "status", "STATE_STORE_RESTORING",
                            "retryAfterSeconds", 15));
        }

        try {
            ReadOnlyWindowStore<String, CategorySpendingAggregate>
                    store = kafkaStreams.store(
                    StoreQueryParameters.fromNameAndType(
                            AnalyticsTopology.USER_CATEGORY_DAILY_STORE,
                            QueryableStoreTypes.windowStore()));

            String key = userId + "|" + category;
            LocalDate queryDate = date != null
                    ? LocalDate.parse(date) : LocalDate.now();

            Instant start = queryDate
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = start.plus(Duration.ofDays(1));

            WindowStoreIterator<CategorySpendingAggregate> results =
                    store.fetch(key, start, end);

            if (results.hasNext()) {
                return ResponseEntity.ok(results.next().value);
            }

            return ResponseEntity.ok(
                    CategorySpendingAggregate.zero());

        } catch (InvalidStateStoreException e) {
            log.warn("State store not ready: {}", e.getMessage());
            return ResponseEntity.status(503)
                    .body(Map.of("status", "STORE_NOT_READY"));
        }
    }

    @GetMapping("/health/lag")
    public ResponseEntity<?> getStreamLag() {
        Map<org.apache.kafka.common.TopicPartition, Long> offsets =
                kafkaStreams.metrics().entrySet().stream()
                        .filter(e -> e.getKey().name()
                                .contains("records-lag"))
                        .collect(java.util.stream.Collectors.toMap(
                                e -> new org.apache.kafka.common.TopicPartition(
                                        "unknown", 0),
                                e -> (long) e.getValue().metricValue()
                                        .hashCode()));

        return ResponseEntity.ok(Map.of(
                "state", kafkaStreams.state().name(),
                "lag", offsets));
    }
}