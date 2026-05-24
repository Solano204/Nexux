package com.nexus.account.application.query;

import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.AccountEvent;
import com.nexus.account.domain.exception.AccountNotFoundException;
import com.nexus.account.domain.exception.AccessDeniedException;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsDocument;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsRepository;
import com.nexus.account.infrastructure.persistence.AccountEventRepository;
import com.nexus.account.infrastructure.persistence.AccountRepository;
import com.nexus.account.infrastructure.redis.BalanceCacheRepository;
import com.nexus.account.web.dto.response.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Account Query Service — CQRS Read Side.
 *
 * All methods are read-only (@Transactional readOnly=true).
 * Redis cache checked first for balance, falls back to PostgreSQL.
 * Analytics reads from MongoDB (pre-aggregated for fast response).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountQueryService {

    private final AccountRepository accountRepository;
    private final AccountEventRepository accountEventRepository;
    private final AccountAnalyticsRepository analyticsRepository;
    private final BalanceCacheRepository balanceCacheRepository;
    private final ObservationRegistry observationRegistry;

    public List<AccountSummaryResponse> getUserAccounts(UUID userId) {
        Observation obs = Observation.createNotStarted(
                "account.query.list", observationRegistry).start();
        try {
            return accountRepository.findByUserIdOrderByCreatedAtAsc(userId)
                    .stream()
                    .map(this::toSummary)
                    .toList();
        } finally {
            obs.stop();
        }
    }

    public AccountDetailResponse getAccountDetail(UUID accountId,
                                                  UUID requestingUserId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + accountId));

        if (!account.getUserId().equals(requestingUserId)) {
            throw new AccessDeniedException(
                    "Account does not belong to requesting user");
        }

        return toDetail(account);
    }

    public BalanceCacheRepository.BalanceCacheEntry getBalanceCached(
            UUID accountId) {
        return balanceCacheRepository.getBalance(accountId);
    }

    public AccountAnalyticsDocument getAnalytics(UUID accountId) {
        return analyticsRepository.findByAccountId(accountId.toString())
                .orElse(null);
    }

    public Page<AccountEventResponse> getAccountEvents(
            UUID accountId, Pageable pageable) {
        return accountEventRepository
                .findByAccountIdOrderByOccurredAtDesc(accountId, pageable)
                .map(this::toEventResponse);
    }

    private AccountSummaryResponse toSummary(Account a) {
        return new AccountSummaryResponse(
                a.getAccountId().toString(),
                a.getAccountNumber(),
                a.getAccountType().name(),
                a.getCurrency(),
                a.getAvailableBalance(),
                a.getReservedAmount(),
                a.getTotalBalance(),
                a.getStatus().name(),
                a.getCreatedAt().toString()
        );
    }

    private AccountDetailResponse toDetail(Account a) {
        return new AccountDetailResponse(
                a.getAccountId().toString(),
                a.getAccountNumber(),
                a.getAccountType().name(),
                a.getCurrency(),
                a.getAvailableBalance(),
                a.getReservedAmount(),
                a.getTotalBalance(),
                a.getStatus().name(),
                a.getDailyTransactionLimit(),
                a.getDailyTransactionUsed(),
                a.getDailyLimitRemaining(),
                a.getMonthlyTransactionLimit(),
                a.getMonthlyTransactionUsed(),
                a.getMinimumBalance(),
                a.getInterestRate(),
                a.getCreatedAt().toString(),
                a.getFrozenAt() != null ? a.getFrozenAt().toString() : null,
                a.getFrozenReason()
        );
    }

    private AccountEventResponse toEventResponse(AccountEvent e) {
        return new AccountEventResponse(
                e.getEventId().toString(),
                e.getEventType(),
                e.getAmount(),
                e.getAvailableBefore(),
                e.getAvailableAfter(),
                e.getOccurredAt().toString()
        );
    }
}