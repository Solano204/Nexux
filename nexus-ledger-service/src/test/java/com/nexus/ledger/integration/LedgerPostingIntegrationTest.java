package com.nexus.ledger.integration;

import com.nexus.ledger.application.command.LedgerCommandService;
import com.nexus.ledger.application.command.PostLedgerCommand;
import com.nexus.ledger.domain.model.ChartOfAccount;
import com.nexus.ledger.domain.model.enums.PostingType;
import com.nexus.ledger.infrastructure.persistence.ChartOfAccountRepository;
import com.nexus.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
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
class LedgerPostingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("nexus_ledger_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final MongoDBContainer mongo =
            new MongoDBContainer("mongo:7.0");

    @Autowired LedgerCommandService commandService;
    @Autowired LedgerEntryRepository entryRepository;
    @Autowired ChartOfAccountRepository coaRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers",
                () -> "localhost:9092");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Test
    @DisplayName("Double-entry: debit and credit entries both created atomically")
    void doubleEntry_bothEntriesCreated_atomically() {
        UUID txnId = UUID.randomUUID();
        var command = buildCommand(txnId, "1000.00");

        var result = commandService.postDoubleEntry(command);

        assertThat(result).isNotNull();
        assertThat(result.debitEntryId()).isNotNull();
        assertThat(result.creditEntryId()).isNotNull();
        assertThat(result.idempotentReplay()).isFalse();

        // Verify both entries exist in PostgreSQL
        var entries = entryRepository
                .findByPostingIdOrderByEntryNumberAsc(result.postingId());

        assertThat(entries).hasSize(2);

        var debit = entries.stream()
                .filter(e -> e.getEntryType().name().equals("DEBIT"))
                .findFirst().orElseThrow();
        var credit = entries.stream()
                .filter(e -> e.getEntryType() .name().equals("CREDIT"))
                .findFirst().orElseThrow();

        // Amounts must match — double-entry invariant
        assertThat(debit.getAmount())
                .isEqualByComparingTo(credit.getAmount());

        // Running balances are non-negative
        assertThat(debit.getRunningBalance())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(credit.getRunningBalance())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Checksums are valid
        assertThat(debit.isChecksumValid()).isTrue();
        assertThat(credit.isChecksumValid()).isTrue();
    }

    @Test
    @DisplayName("Idempotency: same transactionId produces same result")
    void idempotent_sameTransactionId_returnsSameResult() {
        UUID txnId = UUID.randomUUID();
        var command = buildCommand(txnId, "500.00");

        var first = commandService.postDoubleEntry(command);
        var second = commandService.postDoubleEntry(command);

        assertThat(first.postingId())
                .isEqualTo(second.postingId());
        assertThat(second.idempotentReplay()).isTrue();

        // Only 2 entries total (not 4)
        var entries = entryRepository
                .findByPostingIdOrderByEntryNumberAsc(first.postingId());
        assertThat(entries).hasSize(2);
    }

    @Test
    @DisplayName("Immutability: attempting to update entry throws exception")
    void immutability_updateAttempt_throwsDatabaseException() {
        UUID txnId = UUID.randomUUID();
        var command = buildCommand(txnId, "200.00");
        var result = commandService.postDoubleEntry(command);

        var entry = entryRepository
                .findById(result.debitEntryId()).orElseThrow();
        assertThat(entry.isChecksumValid()).isTrue();

        // Direct JDBC update attempt is rejected by the enforce_ledger_immutability
        // trigger (V1__create_ledger_entries.sql), regardless of application code.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = ? WHERE entry_id = ?",
                new BigDecimal("999999.00"), entry.getEntryId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    private PostLedgerCommand buildCommand(UUID txnId, String amount) {
        return PostLedgerCommand.builder()
                .transactionId(txnId)
                .sourceAccountId(seedAccount().getAccountId())
                .targetAccountId(seedAccount().getAccountId())
                .amount(new BigDecimal(amount))
                .currency("MXN")
                .postingType(PostingType.TRANSFER)
                .description("Integration test transfer")
                .sagaId(UUID.randomUUID().toString())
                .traceId("test-trace")
                .build();
    }

    // postDoubleEntry looks accounts up in the chart of accounts
    // (coaRepository.findByAccountIdAndIsActiveTrue) before posting -
    // a bare random UUID with no chart-of-accounts row is rejected as
    // AccountNotFoundException.
    private ChartOfAccount seedAccount() {
        return coaRepository.save(ChartOfAccount.builder()
                .accountId(UUID.randomUUID())
                .accountNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .accountName("Ledger Posting Test Account")
                .accountType("ASSET")
                .accountSubtype("USER_CHECKING")
                .normalBalance("DEBIT")
                .currency("MXN")
                .isUserAccount(true)
                .isActive(true)
                .openingBalance(new BigDecimal("100000.00"))
                .build());
    }
}
