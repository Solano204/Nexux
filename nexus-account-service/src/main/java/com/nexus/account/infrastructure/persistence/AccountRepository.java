package com.nexus.account.infrastructure.persistence;

import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.enums.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account Repository — JPA data access.
 *
 * Critical: findWithLockById uses SELECT FOR UPDATE.
 * This is the row-level locking mechanism that prevents
 * double-spending. Used by ALL balance-modifying operations.
 *
 * The lock_timeout PostgreSQL parameter (5000ms) prevents
 * indefinite lock waits.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * SELECT FOR UPDATE — Acquires row-level exclusive lock.
     * BLOCKS until lock is available (max 5 seconds = lock_timeout).
     *
     * Used by: ReserveBalance, ReleaseBalance, FinalizeTransfer
     *
     * This is why double-spending is mathematically impossible:
     * only ONE transaction can hold this lock at a time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("SELECT a FROM Account a WHERE a.accountId = :id")
    Optional<Account> findWithLockById(@Param("id") UUID id);

    /**
     * Load two accounts with locks in CONSISTENT ORDER.
     * Prevents deadlocks when both source and target need locking.
     *
     * Lock ordering: always by accountId UUID ascending.
     * If A < B: lock A first, then B. Always. No exceptions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("""
        SELECT a FROM Account a
        WHERE a.accountId IN :ids
        ORDER BY a.accountId ASC
        """)
    List<Account> findWithLocksForTransfer(@Param("ids") List<UUID> ids);

    List<Account> findByUserIdOrderByCreatedAtAsc(UUID userId);

    List<Account> findByUserIdAndStatus(UUID userId, AccountStatus status);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByUserIdAndStatus(UUID userId, AccountStatus status);

    @Query("""
        SELECT a FROM Account a
        WHERE a.status = 'ACTIVE'
        AND a.dailyResetAt < :before
        """)
    List<Account> findAccountsDueForDailyReset(
            @Param("before") Instant before);
}