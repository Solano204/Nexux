package com.nexus.transaction.infrastructure.persistence;

import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Page<Transaction> findByUserIdOrderByInitiatedAtDesc(UUID userId, Pageable pageable);

    Page<Transaction> findByUserIdAndStatusOrderByInitiatedAtDesc(
            UUID userId, TransactionStatus status, Pageable pageable);

    Optional<Transaction> findBySagaId(UUID sagaId);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.userId = :userId
        AND t.initiatedAt BETWEEN :from AND :to
        ORDER BY t.initiatedAt DESC
        """)
    List<Transaction> findByUserIdAndDateRange(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status IN :statuses
        AND t.initiatedAt < :before
        """)
    List<Transaction> findStuckTransactions(
            @Param("statuses") List<TransactionStatus> statuses,
            @Param("before") Instant before);

    @Modifying
    @Query("""
        UPDATE Transaction t
        SET t.status = :newStatus,
            t.failureReason = :reason,
            t.failedAt = :now,
            t.updatedAt = :now
        WHERE t.transactionId = :id
        AND t.status = :currentStatus
        """)
    int compareAndSetStatus(
            @Param("id") UUID id,
            @Param("currentStatus") TransactionStatus currentStatus,
            @Param("newStatus") TransactionStatus newStatus,
            @Param("reason") String reason,
            @Param("now") Instant now);
}