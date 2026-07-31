package com.nexus.account.integration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The public integration contract account-service exposes to other
 * services, deliberately separate from the internal {@code Account} JPA
 * entity (com.nexus.account.domain.model.Account). See
 * CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 2 for the audit
 * this formalizes.
 *
 * Every field on {@code Account} that is internal risk/limit-management
 * detail is deliberately excluded here, even though a consumer could
 * theoretically find it useful someday: dailyTransactionLimit/
 * dailyTransactionUsed/dailyResetAt, monthlyTransactionLimit/
 * monthlyTransactionUsed, minimumBalance, interestRate, pendingCredit,
 * reservedAmount, totalBalance (a generated column, an implementation
 * detail of how the balance is computed, not a fact itself). Exposing any
 * of these would couple every consumer to account-service's internal risk
 * policy shape - changing how daily limits work internally would then also
 * be an integration-contract breaking change, which defeats the point of
 * having a separate contract at all.
 *
 * All BigDecimal fields are @JsonFormat(Shape.STRING) - matches the
 * platform-wide convention already used everywhere else (every payload
 * builder across the codebase calls .toPlainString() on money values
 * instead of letting Jackson serialize BigDecimal as a JSON number,
 * avoiding float-precision ambiguity for consumers in other languages).
 * Without this, these records would silently change the wire format from
 * "amount": "500.0000" (string) to "amount": 500.0000 (number) versus what
 * every existing publisher in this codebase actually sends.
 */
public sealed interface AccountIntegrationEvent {

    UUID accountId();
    Instant occurredAt();

    record AccountCreated(
            UUID accountId,
            String accountNumber,
            String accountType,
            UUID userId,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal initialBalance,
            // @JsonProperty("createdAt"): preserves the exact wire field
            // name the old buildAccountCreatedPayload() ObjectNode builder
            // used - occurredAt is the Java-side name (uniform across the
            // sealed interface), but silently renaming the JSON field on
            // an already-published event would be an undocumented breaking
            // change for any consumer reading "createdAt" today, exactly
            // what this whole guide (Tolerant Reader, stable contracts) is
            // about avoiding.
            @JsonProperty("createdAt") Instant occurredAt
    ) implements AccountIntegrationEvent {}

    record BalanceReserved(
            UUID accountId,
            UUID transactionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reservedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal newAvailableBalance,
            @JsonProperty("reservedAt") Instant occurredAt
    ) implements AccountIntegrationEvent {}

    record BalanceReleased(
            UUID accountId,
            UUID transactionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal releasedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal newAvailableBalance,
            @JsonProperty("releasedAt") Instant occurredAt
    ) implements AccountIntegrationEvent {}

    // accountId here is the SOURCE (debited) account - matches the old
    // buildFinalizedPayload()'s "accountId"/"targetAccountId" wire fields,
    // where the debit side is always published as the primary accountId.
    record BalanceFinalizedDebit(
            UUID accountId,
            UUID targetAccountId,
            UUID transactionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debitedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal newAvailableBalance,
            @JsonProperty("finalizedAt") Instant occurredAt
    ) implements AccountIntegrationEvent {}

    // sourceAccountId is the counterpart account for this credit - for a
    // transfer, the debited account; for a deposit (creditAccount()), the
    // synthetic EXTERNAL_FUNDS UUID used platform-wide as the logical
    // debit source for money entering from outside NEXUS.
    record BalanceCredited(
            UUID accountId,
            UUID sourceAccountId,
            UUID transactionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal creditedAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal newAvailableBalance,
            @JsonProperty("creditedAt") Instant occurredAt
    ) implements AccountIntegrationEvent {}
}
