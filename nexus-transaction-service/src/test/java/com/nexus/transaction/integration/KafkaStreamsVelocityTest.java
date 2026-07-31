package com.nexus.transaction.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.transaction.infrastructure.streams.TransactionVelocityTopology;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory Kafka Streams test (TopologyTestDriver — no broker) for
 * TransactionVelocityTopology, the 5-minute rolling window that feeds
 * fraud-service's VelocityCheckTool (topic transactions.velocity). Wires
 * the SAME builder-mutation method Spring calls via @Autowired at runtime
 * (buildVelocityTopology(StreamsBuilder)), just invoked directly against a
 * builder this test owns.
 */
@Tag("integration")
class KafkaStreamsVelocityTest {

    private TopologyTestDriver driver;
    private TestInputTopic<String, JsonNode> inputTopic;
    private TestOutputTopic<String, JsonNode> outputTopic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        new TransactionVelocityTopology(objectMapper).buildVelocityTopology(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "velocity-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        driver = new TopologyTestDriver(builder.build(), props);

        JsonSerde<JsonNode> jsonSerde = new JsonSerde<>(JsonNode.class, objectMapper);
        jsonSerde.ignoreTypeHeaders();

        inputTopic = driver.createInputTopic("transactions.initiated",
                new StringSerializer(), jsonSerde.serializer());
        outputTopic = driver.createOutputTopic("transactions.velocity",
                new StringDeserializer(), jsonSerde.deserializer());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.close();
    }

    private JsonNode transaction(String userId, String amount) {
        return objectMapper.createObjectNode()
                .put("transactionId", UUID.randomUUID().toString())
                .put("userId", userId)
                .put("amount", amount);
    }

    @Test
    @DisplayName("re-keys by userId regardless of the input record key")
    void velocityTopology_reKeysByUserId() {
        inputTopic.pipeInput("some-partition-key", transaction("user-1", "100.00"));

        List<org.apache.kafka.streams.test.TestRecord<String, JsonNode>> records = outputTopic.readRecordsToList();

        assertThat(records).isNotEmpty();
        assertThat(records.get(records.size() - 1).key()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("aggregates count/totalAmount/avgAmount/maxAmount across multiple transactions in the same window")
    void velocityTopology_aggregatesWithinWindow() {
        inputTopic.pipeInput("k1", transaction("user-1", "100.00"));
        inputTopic.pipeInput("k2", transaction("user-1", "50.00"));
        inputTopic.pipeInput("k3", transaction("user-1", "300.00"));

        var records = outputTopic.readRecordsToList();
        var last = records.get(records.size() - 1).value();

        assertThat(last.path("count").asInt()).isEqualTo(3);
        assertThat(new BigDecimal(last.path("totalAmount").asText())).isEqualByComparingTo("450.00");
        assertThat(new BigDecimal(last.path("maxAmount").asText())).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(last.path("avgAmount").asText())).isEqualByComparingTo("150.0000");
    }

    @Test
    @DisplayName("different users get independent velocity windows")
    void velocityTopology_independentPerUser() {
        inputTopic.pipeInput("k1", transaction("user-A", "500.00"));
        inputTopic.pipeInput("k2", transaction("user-B", "10.00"));
        inputTopic.pipeInput("k3", transaction("user-A", "20.00"));

        var records = outputTopic.readRecordsToList();

        var userALatest = records.stream()
                .filter(r -> r.key().equals("user-A"))
                .reduce((first, second) -> second).orElseThrow();
        var userBLatest = records.stream()
                .filter(r -> r.key().equals("user-B"))
                .reduce((first, second) -> second).orElseThrow();

        assertThat(userALatest.value().path("count").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(userALatest.value().path("totalAmount").asText())).isEqualByComparingTo("520.00");
        assertThat(userBLatest.value().path("count").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(userBLatest.value().path("totalAmount").asText())).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("single transaction produces a window with count=1 and avgAmount equal to the transaction amount")
    void velocityTopology_singleTransaction_countIsOne() {
        inputTopic.pipeInput("k1", transaction("user-solo", "75.50"));

        var records = outputTopic.readRecordsToList();
        var last = records.get(records.size() - 1).value();

        assertThat(last.path("count").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(last.path("avgAmount").asText())).isEqualByComparingTo("75.5000");
    }
}
