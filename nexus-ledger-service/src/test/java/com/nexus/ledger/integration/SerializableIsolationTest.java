package com.nexus.ledger.integration;

import com.nexus.ledger.application.command.LedgerCommandService;
import com.nexus.ledger.application.command.PostLedgerCommand;
import com.nexus.ledger.domain.model.ChartOfAccount;
import com.nexus.ledger.domain.model.enums.PostingType;
import com.nexus.ledger.infrastructure.persistence.ChartOfAccountRepository;
import com.nexus.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the SERIALIZABLE isolation guarantee LedgerCommandService relies
 * on (Javadoc: "SERIALIZABLE isolation guarantees these values are
 * consistent — no concurrent transaction can insert a new entry for these
 * accounts between now and commit"). Fires concurrent postDoubleEntry()
 * calls that all debit the SAME source account and asserts the two things
 * that would break under a weaker isolation level: running_balance never
 * goes negative, and every successfully-posted debit's running_balance is
 * unique (no two postings computed from the same stale "current balance"
 * read — the classic lost-update race SERIALIZABLE exists to prevent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class SerializableIsolationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("nexus_ledger_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @Autowired LedgerCommandService commandService;
    @Autowired LedgerEntryRepository entryRepository;
    @Autowired ChartOfAccountRepository coaRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    private ChartOfAccount seedAccount(BigDecimal openingBalance) {
        ChartOfAccount account = ChartOfAccount.builder()
                .accountId(UUID.randomUUID())
                // account_number is VARCHAR(30) - full UUID (36 chars) overflows it
                .accountNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .accountName("Serializable Isolation Test Account")
                .accountType("ASSET")
                .accountSubtype("USER_CHECKING")
                .normalBalance("DEBIT")
                .currency("MXN")
                .isUserAccount(true)
                .isActive(true)
                .openingBalance(openingBalance)
                .build();
        return coaRepository.save(account);
    }

    private PostLedgerCommand buildCommand(UUID sourceAccountId, UUID targetAccountId, String amount) {
        return PostLedgerCommand.builder()
                .transactionId(UUID.randomUUID())
                .sourceAccountId(sourceAccountId)
                .targetAccountId(targetAccountId)
                .amount(new BigDecimal(amount))
                .currency("MXN")
                .postingType(PostingType.TRANSFER)
                .description("Serializable isolation test transfer")
                .sagaId(UUID.randomUUID().toString())
                .traceId("test-trace-serializable")
                .build();
    }

    @Test
    @DisplayName("Concurrent postings against the same source account never produce a negative or duplicated running balance")
    void concurrentPostings_sameSourceAccount_noLostUpdates() throws Exception {
        ChartOfAccount source = seedAccount(new BigDecimal("10000.00"));
        ChartOfAccount target = seedAccount(BigDecimal.ZERO);

        int concurrentPostings = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentPostings);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < concurrentPostings; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await();
                    commandService.postDoubleEntry(
                            buildCommand(source.getAccountId(), target.getAccountId(), "100.00"));
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // SERIALIZABLE isolation is expected to reject some
                    // concurrent transactions outright (Postgres throws a
                    // serialization failure) rather than silently
                    // corrupting running_balance — that rejection is the
                    // correctness guarantee under test, not a test failure.
                    conflictCount.incrementAndGet();
                }
            }, executor));
        }

        startLatch.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get() + conflictCount.get()).isEqualTo(concurrentPostings);
        assertThat(successCount.get()).isGreaterThan(0);

        List<BigDecimal> debitRunningBalances = entryRepository
                .findByAccountIdOrderByEntryNumberDesc(source.getAccountId(),
                        org.springframework.data.domain.PageRequest.of(0, concurrentPostings))
                .getContent()
                .stream()
                .filter(e -> e.getEntryType() == com.nexus.ledger.domain.model.enums.EntryType.DEBIT)
                .map(com.nexus.ledger.domain.model.LedgerEntry::getRunningBalance)
                .toList();

        // Every successful posting must have observed a DISTINCT running
        // balance — two postings computing the same running_balance from
        // the same stale read is exactly the lost-update bug SERIALIZABLE
        // prevents.
        assertThat(debitRunningBalances).hasSize(successCount.get());
        assertThat(debitRunningBalances).doesNotHaveDuplicates();
        assertThat(debitRunningBalances).allSatisfy(balance ->
                assertThat(balance).isGreaterThanOrEqualTo(BigDecimal.ZERO));

        // Final balance must equal opening balance minus exactly the
        // successful debits — no more, no less.
        BigDecimal expectedFinal = new BigDecimal("10000.00")
                .subtract(new BigDecimal("100.00").multiply(BigDecimal.valueOf(successCount.get())));
        BigDecimal actualFinal = entryRepository.findLatestRunningBalance(source.getAccountId())
                .orElseThrow();
        assertThat(actualFinal).isEqualByComparingTo(expectedFinal);
    }

    @Test
    @DisplayName("Sequential postings against the same account always see the previous posting's running balance")
    void sequentialPostings_sameAccount_runningBalanceMonotonicallyDecreases() {
        ChartOfAccount source = seedAccount(new BigDecimal("1000.00"));
        ChartOfAccount target = seedAccount(BigDecimal.ZERO);

        BigDecimal previousBalance = new BigDecimal("1000.00");
        for (int i = 0; i < 5; i++) {
            var result = commandService.postDoubleEntry(
                    buildCommand(source.getAccountId(), target.getAccountId(), "50.00"));
            var debitEntry = entryRepository.findById(result.debitEntryId()).orElseThrow();

            assertThat(debitEntry.getRunningBalance())
                    .isEqualByComparingTo(previousBalance.subtract(new BigDecimal("50.00")));
            previousBalance = debitEntry.getRunningBalance();
        }

        assertThat(previousBalance).isEqualByComparingTo("750.00");
    }
}
