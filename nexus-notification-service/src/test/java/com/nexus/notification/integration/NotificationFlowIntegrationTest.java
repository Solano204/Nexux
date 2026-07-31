package com.nexus.notification.integration;

import com.nexus.notification.application.ai.NotificationContentGenerator;
import com.nexus.notification.domain.model.NotificationContent;
import com.nexus.notification.domain.model.NotificationDocument;
import com.nexus.notification.domain.model.enums.NotificationTone;
import com.nexus.notification.infrastructure.mongodb.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end flow: a real Kafka "transactions.completed" message drives the
 * REAL TransactionEventConsumer -> NotificationProcessingService pipeline
 * (dedup + rate-limit checks against real Redis, real MongoDB persistence)
 * for both sender and receiver. NotificationContentGenerator is mocked to
 * avoid real OpenAI calls (same convention as every other AI-backed
 * integration test in this platform) — everything downstream of content
 * generation (channel selection, IN_APP delivery via real Mongo+Redis,
 * document persistence) is real. Push/Email/SMS are never exercised here:
 * the default preferences this flow falls back to
 * (no PreferencesRepository seed) only enable IN_APP, so
 * AbstractNotificationChannel.send() — final, and backed by real AWS
 * clients this test correctly avoids invoking — is never reached for the
 * other channels.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class NotificationFlowIntegrationTest {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "test");

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired NotificationRepository notificationRepository;

    @MockitoBean NotificationContentGenerator contentGenerator;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    private NotificationContent fixedContent(String title) {
        return new NotificationContent(title, "body text", "short", "Ver detalle", "/transactions/1",
                NotificationTone.POSITIVE, "es", List.of("highlight"), false, null, null);
    }

    @Test
    @DisplayName("transactions.completed with a distinct target user: both sender and receiver get an IN_APP notification persisted to Mongo")
    void transactionCompleted_differentSenderAndReceiver_notifiesBoth() {
        when(contentGenerator.generate(any(), any(), any())).thenReturn(fixedContent("Transferencia completada"));

        String senderId = "user-" + UUID.randomUUID();
        String receiverId = "user-" + UUID.randomUUID();
        String transactionId = UUID.randomUUID().toString();

        String event = """
                {
                  "transactionId": "%s",
                  "sourceUserId": "%s",
                  "targetUserId": "%s",
                  "amount": "250.00",
                  "currency": "MXN",
                  "transactionType": "INTERNAL_TRANSFER",
                  "targetAccountNumber": "ACC-999",
                  "sourceAccountNumber": "ACC-111",
                  "sourceNewBalance": "750.00",
                  "targetNewBalance": "250.00",
                  "traceId": "trace-flow-it"
                }
                """.formatted(transactionId, senderId, receiverId);
        kafkaTemplate.send("transactions.completed", transactionId, event);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            var senderNotifications = notificationRepository
                    .findByUserIdOrderByCreatedAtDesc(senderId, PageRequest.of(0, 10));
            assertThat(senderNotifications.getContent()).isNotEmpty();
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var receiverNotifications = notificationRepository
                    .findByUserIdOrderByCreatedAtDesc(receiverId, PageRequest.of(0, 10));
            assertThat(receiverNotifications.getContent()).isNotEmpty();
        });

        NotificationDocument senderDoc = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(senderId, PageRequest.of(0, 10)).getContent().get(0);
        assertThat(senderDoc.getOverallStatus()).isEqualTo("DELIVERED");
        assertThat(senderDoc.getChannels()).containsKey("IN_APP");
        assertThat(senderDoc.getChannels().get("IN_APP").status()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("transactions.completed redelivered with the same transactionId is deduplicated (no second notification)")
    void transactionCompleted_redelivered_isDeduplicated() {
        when(contentGenerator.generate(any(), any(), any())).thenReturn(fixedContent("Transferencia completada"));

        String senderId = "user-" + UUID.randomUUID();
        String transactionId = UUID.randomUUID().toString();

        String event = """
                {
                  "transactionId": "%s",
                  "sourceUserId": "%s",
                  "targetUserId": null,
                  "amount": "100.00",
                  "currency": "MXN",
                  "transactionType": "INTERNAL_TRANSFER",
                  "traceId": "trace-flow-it-dup"
                }
                """.formatted(transactionId, senderId);

        kafkaTemplate.send("transactions.completed", transactionId, event);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var notifications = notificationRepository
                    .findByUserIdOrderByCreatedAtDesc(senderId, PageRequest.of(0, 10));
            assertThat(notifications.getContent()).hasSize(1);
        });

        // Same transactionId redelivered (Kafka at-least-once)
        kafkaTemplate.send("transactions.completed", transactionId, event);

        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var notifications = notificationRepository
                    .findByUserIdOrderByCreatedAtDesc(senderId, PageRequest.of(0, 10));
            assertThat(notifications.getContent()).hasSize(1); // still just one
        });
    }

    @Test
    @DisplayName("transactions.completed with no target user only notifies the sender")
    void transactionCompleted_noTargetUser_onlyNotifiesSender() {
        when(contentGenerator.generate(any(), any(), any())).thenReturn(fixedContent("Transferencia completada"));

        String senderId = "user-" + UUID.randomUUID();
        String transactionId = UUID.randomUUID().toString();

        String event = """
                {
                  "transactionId": "%s",
                  "sourceUserId": "%s",
                  "amount": "40.00",
                  "currency": "MXN",
                  "transactionType": "CASH_OUT",
                  "traceId": "trace-flow-it-notarget"
                }
                """.formatted(transactionId, senderId);
        kafkaTemplate.send("transactions.completed", transactionId, event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var notifications = notificationRepository
                    .findByUserIdOrderByCreatedAtDesc(senderId, PageRequest.of(0, 10));
            assertThat(notifications.getContent()).hasSize(1);
        });
    }
}
