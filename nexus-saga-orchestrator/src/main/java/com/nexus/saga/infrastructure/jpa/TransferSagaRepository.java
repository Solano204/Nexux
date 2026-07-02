package com.nexus.saga.infrastructure.jpa;

import com.nexus.saga.domain.model.transfer.TransferSagaState;
import com.nexus.saga.domain.model.transfer.TransferStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferSagaRepository
        extends JpaRepository<TransferSagaState, UUID> {

    Optional<TransferSagaState> findByTransactionId(UUID transactionId);

    List<TransferSagaState> findByCurrentStep(TransferStep step);

    List<TransferSagaState> findByCurrentStepNotInAndExpiresAtBefore(
            List<TransferStep> terminalSteps, Instant before);

    Optional<TransferSagaState> findByReviewId(UUID reviewId);

    long countByCurrentStepIn(List<TransferStep> steps);
}