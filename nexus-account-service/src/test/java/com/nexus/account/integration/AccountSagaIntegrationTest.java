package com.nexus.account.integration;

import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.BalanceReservation;
import com.nexus.account.domain.model.enums.AccountStatus;
import com.nexus.account.domain.model.enums.AccountType;
import com.nexus.account.domain.model.enums.ReservationStatus;
import com.nexus.account.infrastructure.persistence.AccountRepository;
import com.nexus.account.infrastructure.persistence.BalanceReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end SAGA participant test for account-service: publishes real
 * saga.commands messages on a real Kafka broker (Testcontainers) and drives
 * the REAL SagaCommandConsumer -> AccountCommandService -> Postgres path.
 * Nothing here is mocked except the counterpart services that would send
 * these commands in production (saga-orchestrator) — their exact JSON shape
 * is reproduced here, cross-referenced against
 * TransferSagaProcessor.buildReserveBalanceCommand()'s payload fields.
 *
 * Was a 4-line empty stub; account-service's own SAGA command handling
 * (ReserveBalanceCommand / ReleaseBalanceCommand / FinalizeTransferCommand)
 * had zero coverage above the mocked unit-test layer before this.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class AccountSagaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("nexus_account_test")
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

    @Container
    static final MongoDBContainer mongo =
            new MongoDBContainer("mongo:7.0");

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired AccountRepository accountRepository;
    @Autowired BalanceReservationRepository reservationRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    private Account createTestAccount(BigDecimal balance) {
        Account account = Account.builder()
                .accountId(UUID.randomUUID())
                .accountNumber(String.format("%04d-%04d-%04d-%04d",
                        new Random().nextInt(10000), new Random().nextInt(10000),
                        new Random().nextInt(10000), new Random().nextInt(10000)))
                .userId(UUID.randomUUID())
                .accountType(AccountType.CHECKING)
                .currency("MXN")
                .status(AccountStatus.ACTIVE)
                .availableBalance(balance)
                .reservedAmount(BigDecimal.ZERO)
                .pendingCredit(BigDecimal.ZERO)
                .dailyTransactionLimit(new BigDecimal("50000.00"))
                .dailyTransactionUsed(BigDecimal.ZERO)
                .monthlyTransactionLimit(new BigDecimal("500000.00"))
                .monthlyTransactionUsed(BigDecimal.ZERO)
                .minimumBalance(BigDecimal.ZERO)
                .interestRate(BigDecimal.ZERO)
                .dailyResetAt(java.time.Instant.now())
                .build();
        return accountRepository.save(account);
    }

    private void publishSagaCommand(String commandType, String sagaId, String payloadJson) {
        String command = """
                {
                  "commandType": "%s",
                  "targetService": "nexus-account-service",
                  "sagaId": "%s",
                  "commandId": "%s",
                  "traceId": "trace-account-saga-it",
                  "payload": %s
                }
                """.formatted(commandType, sagaId, UUID.randomUUID(), payloadJson);
        kafkaTemplate.send("saga.commands", sagaId, command);
    }

    @Test
    @DisplayName("ReserveBalanceCommand: real Kafka message reserves funds in real Postgres")
    void reserveBalanceCommand_realMessage_reservesFundsInPostgres() {
        Account account = createTestAccount(new BigDecimal("1000.00"));
        UUID transactionId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();

        publishSagaCommand("ReserveBalanceCommand", sagaId, """
                {"accountId": "%s", "transactionId": "%s", "amount": "300.00"}
                """.formatted(account.getAccountId(), transactionId));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getReservedAmount()).isEqualByComparingTo("300.00");
            assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("700.00");
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            BalanceReservation reservation = reservationRepository
                    .findByAccountIdAndTransactionId(account.getAccountId(), transactionId)
                    .orElseThrow();
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
            assertThat(reservation.getReservedAmount()).isEqualByComparingTo("300.00");
        });
    }

    @Test
    @DisplayName("ReserveBalanceCommand redelivered with same transactionId: idempotent, no double reservation")
    void reserveBalanceCommand_redelivered_isIdempotent() {
        Account account = createTestAccount(new BigDecimal("1000.00"));
        UUID transactionId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();
        String payload = """
                {"accountId": "%s", "transactionId": "%s", "amount": "250.00"}
                """.formatted(account.getAccountId(), transactionId);

        publishSagaCommand("ReserveBalanceCommand", sagaId, payload);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getReservedAmount()).isEqualByComparingTo("250.00");
        });

        // Kafka at-least-once redelivery of the exact same command
        publishSagaCommand("ReserveBalanceCommand", sagaId, payload);

        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ofSeconds(2)).untilAsserted(() -> {
            Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getReservedAmount()).isEqualByComparingTo("250.00"); // not 500
            assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("750.00");
        });
    }

    @Test
    @DisplayName("ReleaseBalanceCommand: SAGA compensation restores funds via real Kafka + Postgres")
    void releaseBalanceCommand_compensation_restoresFunds() {
        Account account = createTestAccount(new BigDecimal("1000.00"));
        UUID transactionId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();

        publishSagaCommand("ReserveBalanceCommand", sagaId, """
                {"accountId": "%s", "transactionId": "%s", "amount": "400.00"}
                """.formatted(account.getAccountId(), transactionId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getReservedAmount()).isEqualByComparingTo("400.00");
        });

        publishSagaCommand("ReleaseBalanceCommand", sagaId, """
                {"accountId": "%s", "transactionId": "%s", "amount": "400.00"}
                """.formatted(account.getAccountId(), transactionId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getReservedAmount()).isEqualByComparingTo("0.00");
            assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("1000.00");
        });
    }

    @Test
    @DisplayName("Command targeting a different service is ignored (not consumed as this service's own)")
    void commandForDifferentService_isIgnored() {
        Account account = createTestAccount(new BigDecimal("1000.00"));
        UUID transactionId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();

        String command = """
                {
                  "commandType": "ReserveBalanceCommand",
                  "targetService": "nexus-ledger-service",
                  "sagaId": "%s",
                  "commandId": "%s",
                  "traceId": "trace-account-saga-it",
                  "payload": {"accountId": "%s", "transactionId": "%s", "amount": "400.00"}
                }
                """.formatted(sagaId, UUID.randomUUID(), account.getAccountId(), transactionId);
        kafkaTemplate.send("saga.commands", sagaId, command);

        // Give the consumer time to (not) process it, then assert no change
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
            assertThat(reloaded.getReservedAmount()).isEqualByComparingTo("0.00");
        });
    }
}
