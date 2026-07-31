package com.nexus.analytics.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.analytics.streams.AnalyticsTopology;
import com.nexus.analytics.streams.aggregate.CategorySpendingAggregate;
import com.nexus.analytics.streams.aggregate.VolumeAggregate;
import com.nexus.analytics.streams.serde.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.WindowedSerdes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory Kafka Streams topology test (TopologyTestDriver — no Kafka
 * broker required) for AnalyticsTopology. Feeds real TransactionEvent JSON
 * through the real topology definition and reads back the real windowed
 * aggregates, so a bug in the DSL wiring (wrong key extractor, wrong window
 * size, wrong aggregator) fails here instead of silently producing an
 * empty/wrong analytics.* topic in production.
 */
@Tag("integration")
class StreamsTopologyTest {

    private TopologyTestDriver driver;
    private TestInputTopic<String, AnalyticsTopology.TransactionEvent> inputTopic;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        Topology topology = new AnalyticsTopology(objectMapper).buildAnalyticsTopology(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        driver = new TopologyTestDriver(topology, props);

        JsonSerde<AnalyticsTopology.TransactionEvent> txSerde =
                new JsonSerde<>(AnalyticsTopology.TransactionEvent.class, objectMapper);
        inputTopic = driver.createInputTopic("transactions.completed",
                new StringSerializer(), txSerde.serializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
    }

    private AnalyticsTopology.TransactionEvent transaction(String userId, String targetUserId,
                                                             BigDecimal amount, String mcc,
                                                             String merchant, String type, Instant completedAt) {
        return new AnalyticsTopology.TransactionEvent(
                java.util.UUID.randomUUID().toString(), userId, targetUserId,
                java.util.UUID.randomUUID().toString(), amount, "MXN", type,
                merchant, mcc, "CDMX", completedAt);
    }

    private TestOutputTopic<org.apache.kafka.streams.kstream.Windowed<String>, CategorySpendingAggregate>
            categoryDailyOutputTopic() {
        JsonSerde<CategorySpendingAggregate> categorySerde =
                new JsonSerde<>(CategorySpendingAggregate.class, objectMapper);
        return driver.createOutputTopic("analytics.category-spending-daily",
                WindowedSerdes.timeWindowedSerdeFrom(String.class, java.time.Duration.ofDays(1).toMillis())
                        .deserializer(),
                categorySerde.deserializer());
    }

    private TestOutputTopic<org.apache.kafka.streams.kstream.Windowed<String>, VolumeAggregate>
            volumeHourlyOutputTopic() {
        JsonSerde<VolumeAggregate> volumeSerde = new JsonSerde<>(VolumeAggregate.class, objectMapper);
        return driver.createOutputTopic("analytics.volume-hourly",
                WindowedSerdes.timeWindowedSerdeFrom(String.class, java.time.Duration.ofHours(1).toMillis())
                        .deserializer(),
                volumeSerde.deserializer());
    }

    @Test
    @DisplayName("Topology A: groceries spending aggregates by userId|category key within the daily window")
    void categorySpendingTopology_aggregatesByUserAndCategory() {
        Instant now = Instant.now();
        inputTopic.pipeInput(transaction("user-1", null, new BigDecimal("100.00"),
                "5411", "Walmart", "PAYMENT", now));
        inputTopic.pipeInput(transaction("user-1", null, new BigDecimal("50.00"),
                "5411", "Soriana", "PAYMENT", now));

        var outputTopic = categoryDailyOutputTopic();
        var records = outputTopic.readRecordsToList();

        assertThat(records).isNotEmpty();
        var last = records.get(records.size() - 1);
        assertThat(last.key().key()).isEqualTo("user-1|groceries");
        assertThat(last.value().getTotalAmount()).isEqualByComparingTo("150.00");
        assertThat(last.value().getTransactionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Topology A: unmapped merchant category codes fall back to other_{mcc} bucket")
    void categorySpendingTopology_unknownMccFallsBackToOtherBucket() {
        Instant now = Instant.now();
        inputTopic.pipeInput(transaction("user-2", null, new BigDecimal("75.00"),
                "9999", "Unknown Merchant", "PAYMENT", now));

        var records = categoryDailyOutputTopic().readRecordsToList();

        assertThat(records).anySatisfy(r -> assertThat(r.key().key()).isEqualTo("user-2|other_9999"));
    }

    @Test
    @DisplayName("Topology A: null/blank merchant category code buckets as OTHER")
    void categorySpendingTopology_blankMccBucketsAsOther() {
        Instant now = Instant.now();
        inputTopic.pipeInput(transaction("user-3", null, new BigDecimal("20.00"),
                null, "Cash Withdrawal", "PAYMENT", now));

        var records = categoryDailyOutputTopic().readRecordsToList();

        assertThat(records).anySatisfy(r -> assertThat(r.key().key()).isEqualTo("user-3|OTHER"));
    }

    @Test
    @DisplayName("Topology B: platform-wide volume aggregates across all users regardless of key")
    void volumeTopology_aggregatesAcrossAllUsersUnderPlatformKey() {
        Instant now = Instant.now();
        inputTopic.pipeInput(transaction("user-1", null, new BigDecimal("100.00"),
                "5411", "Walmart", "PAYMENT", now));
        inputTopic.pipeInput(transaction("user-2", null, new BigDecimal("200.00"),
                "5812", "Restaurant", "PAYMENT", now));

        var records = volumeHourlyOutputTopic().readRecordsToList();

        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(r -> assertThat(r.key().key()).isEqualTo("PLATFORM"));
        var last = records.get(records.size() - 1);
        assertThat(last.value().getTotalVolume()).isEqualByComparingTo("300.00");
        assertThat(last.value().getTransactionCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Two separate users' category spending windows do not cross-contaminate")
    void categorySpendingTopology_differentUsersHaveIndependentAggregates() {
        Instant now = Instant.now();
        inputTopic.pipeInput(transaction("user-A", null, new BigDecimal("500.00"),
                "5411", "Costco", "PAYMENT", now));
        inputTopic.pipeInput(transaction("user-B", null, new BigDecimal("10.00"),
                "5411", "Corner Store", "PAYMENT", now));

        var records = categoryDailyOutputTopic().readRecordsToList();

        var userARecord = records.stream().filter(r -> r.key().key().equals("user-A|groceries")).findFirst();
        var userBRecord = records.stream().filter(r -> r.key().key().equals("user-B|groceries")).findFirst();

        assertThat(userARecord).isPresent();
        assertThat(userBRecord).isPresent();
        assertThat(userARecord.get().value().getTotalAmount()).isEqualByComparingTo("500.00");
        assertThat(userBRecord.get().value().getTotalAmount()).isEqualByComparingTo("10.00");
    }
}
