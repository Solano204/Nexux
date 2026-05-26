package com.nexus.ledger.infrastructure.persistence;

import com.nexus.ledger.domain.model.Posting;
import com.nexus.ledger.domain.model.enums.PostingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Optional<Posting> findByTransactionId(UUID transactionId);

    Page<Posting> findByStatusOrderByPostedAtDesc(
            PostingStatus status, Pageable pageable);

    @Query("""
        SELECT COUNT(p) FROM Posting p
        WHERE p.postedAt >= :from AND p.postedAt < :to
        """)
    long countPostingsInPeriod(
            @Param("from") Instant from,
            @Param("to") Instant to);
}