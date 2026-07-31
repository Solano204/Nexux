package com.nexus.account.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.enums.AccountStatus;
import com.nexus.account.domain.model.enums.AccountType;
import com.nexus.account.integration.AccountIntegrationEvent;
import com.nexus.account.integration.AccountIntegrationEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AccountIntegrationEventMapperTest {

    private final AccountIntegrationEventMapper mapper = new AccountIntegrationEventMapper();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // registers jackson-datatype-jsr310 for Instant

    @Test
    @DisplayName("toAccountCreated: maps only the public fields, not internal risk/limit fields")
    void toAccountCreated_mapsOnlyPublicFields() {
        Account account = Account.builder()
                .accountId(UUID.randomUUID())
                .accountNumber("1234567890")
                .accountType(AccountType.CHECKING)
                .userId(UUID.randomUUID())
                .currency("MXN")
                .status(AccountStatus.ACTIVE)
                .availableBalance(new BigDecimal("500.0000"))
                .reservedAmount(new BigDecimal("999.9999")) // must NOT leak into the event
                .dailyTransactionLimit(new BigDecimal("50000")) // must NOT leak
                .interestRate(new BigDecimal("0.0150")) // must NOT leak
                .build();

        AccountIntegrationEvent.AccountCreated event = mapper.toAccountCreated(account);

        assertThat(event.accountId()).isEqualTo(account.getAccountId());
        assertThat(event.accountNumber()).isEqualTo("1234567890");
        assertThat(event.accountType()).isEqualTo("CHECKING");
        assertThat(event.userId()).isEqualTo(account.getUserId());
        assertThat(event.currency()).isEqualTo("MXN");
        assertThat(event.initialBalance()).isEqualByComparingTo("500.0000");
    }

    @Test
    @DisplayName("toAccountCreated: serializes exactly like the ObjectNode builder it replaced (field names + money-as-string)")
    void toAccountCreated_wireFormatMatchesOriginalPayloadBuilder() throws Exception {
        Account account = Account.builder()
                .accountId(UUID.randomUUID())
                .accountNumber("1234567890")
                .accountType(AccountType.SAVINGS)
                .userId(UUID.randomUUID())
                .currency("MXN")
                .status(AccountStatus.ACTIVE)
                .availableBalance(new BigDecimal("1500.5000"))
                .build();

        AccountIntegrationEvent.AccountCreated event = mapper.toAccountCreated(account);
        ObjectNode json = (ObjectNode) objectMapper.valueToTree(event);

        // Field names must match buildAccountCreatedPayload()'s original
        // ObjectNode output exactly - "occurredAt" (the Java-side sealed
        // interface method name) must NOT leak into the wire format as
        // "createdAt" is what every existing/future consumer expects.
        assertThat(json.has("createdAt")).isTrue();
        assertThat(json.has("occurredAt")).isFalse();

        // Money must serialize as a JSON string, not a number - matches
        // every other payload builder in this codebase (.toPlainString()).
        assertThat(json.get("initialBalance").isTextual())
                .as("initialBalance must be a JSON string like every other money field in this codebase, not a number")
                .isTrue();
        assertThat(json.get("initialBalance").asText()).isEqualTo("1500.5000");
    }

    @Test
    @DisplayName("toBalanceReserved: field names match the original reservedAt (not occurredAt)")
    void toBalanceReserved_wireFieldNameIsReservedAt() throws Exception {
        AccountIntegrationEvent.BalanceReserved event = mapper.toBalanceReserved(
                UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), new BigDecimal("400.00"));

        ObjectNode json = (ObjectNode) objectMapper.valueToTree(event);

        assertThat(json.has("reservedAt")).isTrue();
        assertThat(json.has("occurredAt")).isFalse();
        assertThat(json.get("reservedAmount").isTextual()).isTrue();
        assertThat(json.get("newAvailableBalance").isTextual()).isTrue();
    }

    @Test
    @DisplayName("toBalanceReleased: field names match the original releasedAt (not occurredAt)")
    void toBalanceReleased_wireFieldNameIsReleasedAt() throws Exception {
        AccountIntegrationEvent.BalanceReleased event = mapper.toBalanceReleased(
                UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), new BigDecimal("500.00"));

        ObjectNode json = (ObjectNode) objectMapper.valueToTree(event);

        assertThat(json.has("releasedAt")).isTrue();
        assertThat(json.has("occurredAt")).isFalse();
        assertThat(json.get("releasedAmount").isTextual()).isTrue();
        assertThat(json.get("newAvailableBalance").isTextual()).isTrue();
    }

    @Test
    @DisplayName("toBalanceFinalizedDebit: wire fields match buildFinalizedPayload's original shape (accountId=source, targetAccountId=target, finalizedAt)")
    void toBalanceFinalizedDebit_wireFormatMatchesOriginalPayloadBuilder() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        AccountIntegrationEvent.BalanceFinalizedDebit event = mapper.toBalanceFinalizedDebit(
                source, target, transactionId,
                new BigDecimal("250.00"), new BigDecimal("750.00"));

        ObjectNode json = (ObjectNode) objectMapper.valueToTree(event);

        assertThat(json.get("accountId").asText()).isEqualTo(source.toString());
        assertThat(json.get("targetAccountId").asText()).isEqualTo(target.toString());
        assertThat(json.has("finalizedAt")).isTrue();
        assertThat(json.has("occurredAt")).isFalse();
        assertThat(json.get("debitedAmount").isTextual()).isTrue();
        assertThat(json.get("newAvailableBalance").isTextual()).isTrue();
    }

    @Test
    @DisplayName("toBalanceCredited: wire fields match buildCreditedPayload's original shape (sourceAccountId, creditedAt)")
    void toBalanceCredited_wireFormatMatchesOriginalPayloadBuilder() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        AccountIntegrationEvent.BalanceCredited event = mapper.toBalanceCredited(
                accountId, sourceAccountId, transactionId,
                new BigDecimal("300.00"), new BigDecimal("1300.00"));

        ObjectNode json = (ObjectNode) objectMapper.valueToTree(event);

        assertThat(json.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(json.get("sourceAccountId").asText()).isEqualTo(sourceAccountId.toString());
        assertThat(json.has("creditedAt")).isTrue();
        assertThat(json.has("occurredAt")).isFalse();
        assertThat(json.get("creditedAmount").isTextual()).isTrue();
        assertThat(json.get("newAvailableBalance").isTextual()).isTrue();
    }
}
