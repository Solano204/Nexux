package com.nexus.account.infrastructure.persistence;

import com.nexus.account.domain.model.BalanceReservation;
import com.nexus.account.domain.model.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BalanceReservationRepository
        extends JpaRepository<BalanceReservation, UUID> {

    Optional<BalanceReservation> findByAccountIdAndTransactionId(
            UUID accountId, UUID transactionId);

    List<BalanceReservation> findByAccountIdAndStatus(
            UUID accountId, ReservationStatus status);

    @Query("""
        SELECT r FROM BalanceReservation r
        WHERE r.status = 'ACTIVE'
        AND r.expiresAt < CURRENT_TIMESTAMP
        """)
    List<BalanceReservation> findExpiredActiveReservations();

    @Modifying
    @Query("""
        UPDATE BalanceReservation r
        SET r.status = :status, r.releasedAt = CURRENT_TIMESTAMP
        WHERE r.reservationId = :reservationId
        """)
    int updateStatus(
            @Param("reservationId") UUID reservationId,
            @Param("status") ReservationStatus status);

    /**
     * Count reservations by status — used by Micrometer Gauge
     * in AccountCommandService constructor for active reservation monitoring.
     */
    long countByStatus(ReservationStatus status);
}