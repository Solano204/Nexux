package com.nexus.account.integration;

import com.nexus.account.application.command.AccountCommandService;
import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.enums.AccountStatus;
import com.nexus.account.domain.model.enums.AccountType;
import com.nexus.account.infrastructure.persistence.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class BalanceConcurrencyIntegrationTest {

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

    @Autowired AccountCommandService commandService;
    @Autowired AccountRepository accountRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    @Test
    @DisplayName("Concurrent reserves: only sufficient-fund reserves succeed, no negative balance")
    void concurrentReserves_onlySufficientFundsSucceed() throws Exception {
        // Account with MXN 500
        Account account = createTestAccount(new BigDecimal("500.00"));
        UUID accountId = account.getAccountId();

        // 5 concurrent attempts to reserve MXN 200 each
        // Total requested: MXN 1000, Available: MXN 500
        // Expected: at most 2 succeed (2 × 200 = 400 ≤ 500)
        int concurrentRequests = 5;
        ExecutorService executor =
                Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentRequests; i++) {
            UUID txnId = UUID.randomUUID();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await(); // All goroutines start simultaneously
                    var result = commandService.reserveBalance(
                            accountId, txnId,
                            new BigDecimal("200.00"),
                            "trace-concurrent-test");

                    if (result.success()) successCount.incrementAndGet();
                    else failureCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }, executor));
        }

        startLatch.countDown(); // Release all threads simultaneously
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        // Load final account state from PostgreSQL
        Account finalAccount = accountRepository
                .findById(accountId).orElseThrow();

        // CRITICAL ASSERTIONS:
        assertThat(finalAccount.getAvailableBalance())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // Available balance must NEVER be negative

        assertThat(successCount.get()).isLessThanOrEqualTo(2);
        // At most 2 successes (2 × 200 = 400 ≤ 500, 3 × 200 = 600 > 500)

        // Total reserved must equal what was actually deducted
        BigDecimal expectedReserved = new BigDecimal("200.00")
                .multiply(new BigDecimal(successCount.get()));
        assertThat(finalAccount.getReservedAmount())
                .isEqualByComparingTo(expectedReserved);

        // Available + Reserved = original balance (accounting integrity)
        assertThat(finalAccount.getAvailableBalance()
                .add(finalAccount.getReservedAmount()))
                .isEqualByComparingTo("500.00");

        executor.shutdown();
    }

    @Test
    @DisplayName("Reserve then release: funds fully restored")
    void reserveThenRelease_fundsRestored() throws Exception {
        Account account = createTestAccount(new BigDecimal("1000.00"));
        UUID accountId = account.getAccountId();
        UUID txnId = UUID.randomUUID();

        // Reserve
        var reserveResult = commandService.reserveBalance(
                accountId, txnId,
                new BigDecimal("750.00"),
                "trace-test-001");
        assertThat(reserveResult.success()).isTrue();

        // Verify balance after reserve
        Account afterReserve = accountRepository
                .findById(accountId).orElseThrow();
        assertThat(afterReserve.getAvailableBalance())
                .isEqualByComparingTo("250.00");
        assertThat(afterReserve.getReservedAmount())
                .isEqualByComparingTo("750.00");

        // Release (SAGA compensation)
        var releaseResult = commandService.releaseBalance(
                accountId, txnId,
                new BigDecimal("750.00"),
                "trace-test-001");
        assertThat(releaseResult.success()).isTrue();

        // Verify funds fully restored
        Account afterRelease = accountRepository
                .findById(accountId).orElseThrow();
        assertThat(afterRelease.getAvailableBalance())
                .isEqualByComparingTo("1000.00");
        assertThat(afterRelease.getReservedAmount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Duplicate reserve (idempotent): returns existing without double-reserving")
    void duplicateReserve_isIdempotent() throws Exception {
        Account account = createTestAccount(new BigDecimal("1000.00"));
        UUID accountId = account.getAccountId();
        UUID txnId = UUID.randomUUID();

        // First reserve
        commandService.reserveBalance(accountId, txnId,
                new BigDecimal("300.00"), "trace-001");

        // Second reserve with SAME transactionId (Kafka redelivery)
        var duplicateResult = commandService.reserveBalance(
                accountId, txnId,
                new BigDecimal("300.00"),
                "trace-001");

        assertThat(duplicateResult.success()).isTrue();
        assertThat(duplicateResult.idempotentReplay()).isTrue();

        // Balance shows ONLY ONE reservation (not double)
        Account finalAccount = accountRepository
                .findById(accountId).orElseThrow();
        assertThat(finalAccount.getReservedAmount())
                .isEqualByComparingTo("300.00"); // Not 600
        assertThat(finalAccount.getAvailableBalance())
                .isEqualByComparingTo("700.00"); // Not 400
    }

    private Account createTestAccount(BigDecimal balance) {
        Account account = Account.builder()
                .accountId(UUID.randomUUID())
                .accountNumber(
                        String.format("%04d-%04d-%04d-%04d",
                                new Random().nextInt(10000),
                                new Random().nextInt(10000),
                                new Random().nextInt(10000),
                                new Random().nextInt(10000)))
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
}