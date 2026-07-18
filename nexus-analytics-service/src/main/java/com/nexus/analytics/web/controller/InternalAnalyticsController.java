package com.nexus.analytics.web.controller;

import com.nexus.analytics.streams.aggregate.CategorySpendingAggregate;
import com.nexus.analytics.streams.AnalyticsTopology;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Analytics (Internal)", description = "Interactive Kafka Streams state-store queries — read by fraud-service (velocity), account-service (advisor spending patterns), and risk-scoring-service (volatility).")
public class InternalAnalyticsController {

    // ANTES: private final KafkaStreams kafkaStreams;
    // AHORA:
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;

    private KafkaStreams streams() {
        KafkaStreams ks = streamsBuilderFactoryBean.getKafkaStreams();
        if (ks == null) throw new IllegalStateException("Kafka Streams not initialized yet");
        return ks;
    }

    @Operation(summary = "Query category spending for a user/day", description = "Reads directly from the Kafka Streams state store — 503 with Retry-After if the store is rebalancing or not yet ready, not a transient error to treat as a failure.")
    @ApiResponse(responseCode = "200", description = "Spending aggregate for the category/day (zero-value aggregate if no data)")
    @ApiResponse(responseCode = "503", description = "Streams not ready — retry after the given delay")
    @GetMapping("/category-spending")
    public ResponseEntity<?> getCategorySpending(
            @Parameter(description = "User UUID", required = true) @RequestParam("userId") String userId,
            @Parameter(description = "Spending category", required = true) @RequestParam("category") String category,
            @Parameter(description = "ISO date, defaults to today") @RequestParam(value = "date", required = false) String date) {

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

    @Operation(summary = "Get Kafka Streams state", description = "Quick health check for the streams pipeline itself — RUNNING, REBALANCING, etc.")
    @ApiResponse(responseCode = "200", description = "State retrieved")
    @ApiResponse(responseCode = "503", description = "Streams not initialized or errored")
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