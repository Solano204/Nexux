package com.nexus.analytics.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.streams.aggregate.*;
import com.nexus.analytics.streams.serde.JsonSerde;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Analytics Topology — Six Kafka Streams processing pipelines.
 *
 * Topology A: Spending by category (daily tumbling window, user-level)
 * Topology B: Transaction volume (hourly tumbling window, platform)
 * Topology C: Merchant frequency (30-day sliding window, user-level)
 * Topology D: Weekly spending for anomaly detection
 * Topology E: Income detection (monthly window, receiver-side)
 * Topology F: Daily aggregation for calendar heatmap
 *
 * All topologies share the same input stream from transactions.completed.
 * Kafka Streams fan-out handles the single-stream → multiple-topology split.
 *
 * State stores are RocksDB-backed. Queryable via interactive queries
 * served by InternalAnalyticsController.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AnalyticsTopology {

    private final ObjectMapper objectMapper;

    // Store names — referenced by InternalAnalyticsController
    public static final String USER_CATEGORY_DAILY_STORE =
            "user-category-daily-store";
    public static final String PLATFORM_VOLUME_HOURLY_STORE =
            "platform-volume-hourly-store";
    public static final String USER_CATEGORY_WEEKLY_STORE =
            "user-category-weekly-store";
    public static final String USER_INCOME_MONTHLY_STORE =
            "user-income-monthly-store";
    public static final String USER_MERCHANT_30DAY_STORE =
            "user-merchant-30day-store";
    public static final String USER_DAILY_SPENDING_STORE =
            "user-daily-spending-store";

    @Bean
    public Topology buildAnalyticsTopology(StreamsBuilder builder) {

        // ── Serdes ────────────────────────────────────────────
        JsonSerde<TransactionEvent> txSerde =
                new JsonSerde<>(TransactionEvent.class, objectMapper);
        JsonSerde<CategorySpendingAggregate> categorySerde =
                new JsonSerde<>(CategorySpendingAggregate.class,
                        objectMapper);
        JsonSerde<VolumeAggregate> volumeSerde =
                new JsonSerde<>(VolumeAggregate.class, objectMapper);
        JsonSerde<IncomeAggregate> incomeSerde =
                new JsonSerde<>(IncomeAggregate.class, objectMapper);

        // ── Source stream ──────────────────────────────────────
        KStream<String, TransactionEvent> txStream =
                builder.stream(
                        "transactions.completed",
                        Consumed.with(Serdes.String(), txSerde))
                        .peek((key, tx) -> {
                            if (tx != null) log.info("Analytics received: txId={} userId={} amount={} type={}",
                                    tx.transactionId(), tx.sourceUserId(), tx.amount(), tx.transactionType());
                            else log.warn("Analytics received null TransactionEvent for key={}", key);
                        });

        // ═══════════════════════════════════════════════════════
        // TOPOLOGY A — Spending by Category (Daily, User-Level)
        // ═══════════════════════════════════════════════════════

        txStream
                // Re-key: "{userId}|{category}"
                .selectKey((txId, tx) ->
                        tx.sourceUserId() + "|" +
                                safeCategory(tx.merchantCategoryCode()))

                .groupByKey(Grouped.with(
                        Serdes.String(), txSerde))

                // Daily tumbling window
                .windowedBy(TimeWindows.ofSizeWithNoGrace(
                        Duration.ofDays(1)))

                .aggregate(
                        CategorySpendingAggregate::zero,
                        (key, tx, agg) -> agg.add(
                                tx.amount(),
                                tx.currency(),
                                tx.merchantName()),
                        Materialized
                                .<String,
                                        CategorySpendingAggregate,
                                        WindowStore<Bytes, byte[]>>
                                        as(USER_CATEGORY_DAILY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(categorySerde)
                )
                .toStream()
                .to("analytics.category-spending-daily",
                        Produced.with(
                                WindowedSerdes.timeWindowedSerdeFrom(
                                        String.class, Duration.ofDays(1)
                                                .toMillis()),
                                categorySerde));

        // ═══════════════════════════════════════════════════════
        // TOPOLOGY B — Transaction Volume (Hourly, Platform)
        // ═══════════════════════════════════════════════════════

        txStream
                .selectKey((txId, tx) -> "PLATFORM")
                .groupByKey(Grouped.with(Serdes.String(), txSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(
                        Duration.ofHours(1)))
                .aggregate(
                        VolumeAggregate::zero,
                        (key, tx, agg) -> agg.add(
                                tx.amount(),
                                tx.currency(),
                                tx.transactionType()),
                        Materialized
                                .<String,
                                        VolumeAggregate,
                                        WindowStore<Bytes, byte[]>>
                                        as(PLATFORM_VOLUME_HOURLY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(volumeSerde)
                )
                .toStream()
                .to("analytics.volume-hourly",
                        Produced.with(
                                WindowedSerdes.timeWindowedSerdeFrom(
                                        String.class,
                                        Duration.ofHours(1).toMillis()),
                                volumeSerde));

        // ═══════════════════════════════════════════════════════
        // TOPOLOGY C — Weekly Spending for Anomaly Detection
        // ═══════════════════════════════════════════════════════

        txStream
                .selectKey((txId, tx) ->
                        tx.sourceUserId() + "|" +
                                safeCategory(tx.merchantCategoryCode()))
                .groupByKey(Grouped.with(Serdes.String(), txSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(
                        Duration.ofDays(7)))
                .aggregate(
                        CategorySpendingAggregate::zero,
                        (key, tx, agg) -> agg.add(
                                tx.amount(), tx.currency(),
                                tx.merchantName()),
                        Materialized
                                .<String,
                                        CategorySpendingAggregate,
                                        WindowStore<Bytes, byte[]>>
                                        as(USER_CATEGORY_WEEKLY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(categorySerde)
                )
                .toStream()
                .to("analytics.weekly-spending",
                        Produced.with(
                                WindowedSerdes.timeWindowedSerdeFrom(
                                        String.class,
                                        Duration.ofDays(7).toMillis()),
                                categorySerde));

        // ═══════════════════════════════════════════════════════
        // TOPOLOGY D — Income Detection (Monthly, Receiver)
        // ═══════════════════════════════════════════════════════

        txStream
                .filter((txId, tx) ->
                        tx.targetUserId() != null &&
                                !tx.targetUserId().isBlank())
                .selectKey((txId, tx) -> tx.targetUserId())
                .groupByKey(Grouped.with(Serdes.String(), txSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(
                        Duration.ofDays(30)))
                .aggregate(
                        IncomeAggregate::zero,
                        (key, tx, agg) -> agg.add(
                                tx.amount(), tx.currency(),
                                tx.transactionType()),
                        Materialized
                                .<String,
                                        IncomeAggregate,
                                        WindowStore<Bytes, byte[]>>
                                        as(USER_INCOME_MONTHLY_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(incomeSerde)
                )
                .toStream()
                .to("analytics.income-monthly",
                        Produced.with(
                                WindowedSerdes.timeWindowedSerdeFrom(
                                        String.class,
                                        Duration.ofDays(30).toMillis()),
                                incomeSerde));

        // ═══════════════════════════════════════════════════════
        // TOPOLOGY E — Daily Spending for Calendar Heatmap
        // ═══════════════════════════════════════════════════════

        txStream
                // Key: "{userId}|{YYYY-MM-DD}"
                .selectKey((txId, tx) ->
                        tx.sourceUserId() + "|" +
                                tx.completedAt()
                                        .atZone(ZoneOffset.UTC)
                                        .toLocalDate()
                                        .toString())
                .groupByKey(Grouped.with(Serdes.String(), txSerde))
                // Daily tumbling window for calendar
                .windowedBy(TimeWindows.ofSizeWithNoGrace(
                        Duration.ofDays(1)))
                .aggregate(
                        CategorySpendingAggregate::zero,
                        (key, tx, agg) -> agg.add(
                                tx.amount(), tx.currency(), "ALL"),
                        Materialized
                                .<String,
                                        CategorySpendingAggregate,
                                        WindowStore<Bytes, byte[]>>
                                        as(USER_DAILY_SPENDING_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(categorySerde)
                );

        return builder.build();
    }

    private String safeCategory(String mcc) {
        if (mcc == null || mcc.isBlank()) return "OTHER";
        return switch (mcc) {
            case "5812", "5813", "5814" -> "food_dining";
            case "4111", "4121", "4131" -> "transportation";
            case "5411", "5422", "5441" -> "groceries";
            case "5311", "5600", "5611" -> "shopping";
            case "7011", "7012", "4411" -> "travel";
            case "8011", "8021", "8099" -> "health";
            case "5047", "6159" -> "financial_services";
            default -> "other_" + mcc;
        };
    }

    // ── Inner record: transaction event schema ─────────────────

    public record TransactionEvent(
            String transactionId,
            String sourceUserId,
            String targetUserId,
            String sourceAccountId,
            BigDecimal amount,
            String currency,
            String transactionType,
            String merchantName,
            String merchantCategoryCode,
            String merchantCity,
            Instant completedAt
    ) {}

    public record IncomeAggregate(
            BigDecimal totalAmount,
            int transactionCount,
            String currency,
            Instant lastUpdated
    ) {
        public static IncomeAggregate zero() {
            return new IncomeAggregate(
                    BigDecimal.ZERO, 0, "MXN", null);
        }

        public IncomeAggregate add(BigDecimal amount,
                                   String currency,
                                   String type) {
            return new IncomeAggregate(
                    totalAmount.add(amount),
                    transactionCount + 1,
                    currency != null ? currency : this.currency,
                    Instant.now()
            );
        }
    }
}