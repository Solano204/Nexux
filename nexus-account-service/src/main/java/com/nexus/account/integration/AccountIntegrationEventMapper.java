package com.nexus.account.integration;

import com.nexus.account.domain.model.Account;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Explicit translation from the internal Account domain model to the
 * public AccountIntegrationEvent contract - see AccountIntegrationEvent's
 * Javadoc for which fields are deliberately excluded and why.
 *
 * Only takes the specific values each event needs, not the whole Account -
 * a method that accepted Account for BalanceReserved/BalanceReleased would
 * invite a future edit to "just read amount off the account" instead of
 * being handed the actual reserved/released amount for that operation,
 * which is not always the same as any single field on Account at the time
 * the event fires (see AccountCommandService.buildBalanceReservedPayload
 * for the same reasoning already applied ad-hoc, formalized here).
 */
@Component
public class AccountIntegrationEventMapper {

    public AccountIntegrationEvent.AccountCreated toAccountCreated(Account account) {
        return new AccountIntegrationEvent.AccountCreated(
                account.getAccountId(),
                account.getAccountNumber(),
                account.getAccountType().name(),
                account.getUserId(),
                account.getCurrency(),
                account.getAvailableBalance(),
                Instant.now()
        );
    }

    public AccountIntegrationEvent.BalanceReserved toBalanceReserved(
            UUID accountId, UUID transactionId,
            BigDecimal reservedAmount, BigDecimal newAvailableBalance) {
        return new AccountIntegrationEvent.BalanceReserved(
                accountId, transactionId, reservedAmount, newAvailableBalance, Instant.now());
    }

    public AccountIntegrationEvent.BalanceReleased toBalanceReleased(
            UUID accountId, UUID transactionId,
            BigDecimal releasedAmount, BigDecimal newAvailableBalance) {
        return new AccountIntegrationEvent.BalanceReleased(
                accountId, transactionId, releasedAmount, newAvailableBalance, Instant.now());
    }

    public AccountIntegrationEvent.BalanceFinalizedDebit toBalanceFinalizedDebit(
            UUID sourceAccountId, UUID targetAccountId, UUID transactionId,
            BigDecimal debitedAmount, BigDecimal newAvailableBalance) {
        return new AccountIntegrationEvent.BalanceFinalizedDebit(
                sourceAccountId, targetAccountId, transactionId,
                debitedAmount, newAvailableBalance, Instant.now());
    }

    public AccountIntegrationEvent.BalanceCredited toBalanceCredited(
            UUID accountId, UUID sourceAccountId, UUID transactionId,
            BigDecimal creditedAmount, BigDecimal newAvailableBalance) {
        return new AccountIntegrationEvent.BalanceCredited(
                accountId, sourceAccountId, transactionId,
                creditedAmount, newAvailableBalance, Instant.now());
    }
}
