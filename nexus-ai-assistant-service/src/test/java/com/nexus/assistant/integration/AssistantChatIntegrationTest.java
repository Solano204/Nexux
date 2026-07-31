package com.nexus.assistant.integration;

import com.nexus.assistant.agent.FinancialAssistantAgent;
import com.nexus.assistant.application.ChatService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the chat pipeline's orchestration and analytics
 * side-effect: real Kafka (Testcontainers) receives the real
 * ai.query.logged event that ChatService publishes after a chat turn
 * completes. FinancialAssistantAgent is mocked — it wraps real OpenAI
 * calls (Spring AI ChatClient), which an integration test shouldn't call
 * for the same reason TransferSagaIntegrationTest mocks
 * SagaFailureExplainerService: cost, flakiness, and it isn't what this
 * test is verifying (the Kafka side-effect and Observation lifecycle are).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class AssistantChatIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("nexus_ai_assistant_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "test");

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired ChatService chatService;
    @MockitoBean FinancialAssistantAgent agent;

    private Consumer<String, String> testConsumer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    @BeforeEach
    void setUp() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        testConsumer = new DefaultKafkaConsumerFactory<String, String>(
                props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        testConsumer.subscribe(List.of("ai.query.logged"));
        testConsumer.poll(Duration.ofMillis(100)); // trigger partition assignment
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) testConsumer.close();
    }

    @Test
    @DisplayName("chat(): publishes ai.query.logged to real Kafka after the agent stream completes")
    void chat_completesSuccessfully_publishesAnalyticsEventToKafka() {
        when(agent.chat(anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("Tu saldo es ", "$500.00 MXN"));

        String userId = "user-" + UUID.randomUUID();
        String sessionId = "session-1";

        StepVerifier.create(chatService.chat("cual es mi saldo?", userId, sessionId))
                .expectNext("Tu saldo es ", "$500.00 MXN")
                .verifyComplete();

        ConsumerRecord<String, String> record = pollForRecordMatching(userId);
        assertThat(record).isNotNull();
        assertThat(record.value()).contains("\"eventType\":\"ai.query.logged\"");
        assertThat(record.value()).contains(userId);
        assertThat(record.value()).contains(sessionId);
    }

    @Test
    @DisplayName("chat(): does not publish an analytics event when the agent stream errors")
    void chat_agentErrors_doesNotPublishAnalyticsEvent() {
        when(agent.chat(anyString(), anyString(), anyString()))
                .thenReturn(Flux.error(new RuntimeException("OpenAI timeout")));

        String userId = "user-" + UUID.randomUUID();

        StepVerifier.create(chatService.chat("hola", userId, "session-err"))
                .expectError(RuntimeException.class)
                .verify();

        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(3));
        boolean found = false;
        for (ConsumerRecord<String, String> r : records) {
            if (r.value().contains(userId)) found = true;
        }
        assertThat(found).isFalse();
    }

    private ConsumerRecord<String, String> pollForRecordMatching(String userId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value().contains(userId)) return record;
            }
        }
        return null;
    }
}
