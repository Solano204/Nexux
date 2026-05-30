package com.nexus.saga.web.controller;

import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/internal/v1/sagas")
@RequiredArgsConstructor
public class InternalSagaController {

    private final TransferSagaRepository transferSagaRepository;
    private final OnboardingSagaRepository onboardingRepository;
    private final SagaStepHistoryRepository historyRepository;

    @GetMapping("/transfer/{transactionId}")
    public ResponseEntity<?> getTransferSaga(@PathVariable String transactionId) {
        return transferSagaRepository.findByTransactionId(UUID.fromString(transactionId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/onboarding/{userId}")
    public ResponseEntity<?> getOnboardingSaga(@PathVariable String userId) {
        return onboardingRepository.findByUserId(UUID.fromString(userId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/transfer/{transactionId}/history")
    public ResponseEntity<?> getTransferHistory(@PathVariable String transactionId) {
        return transferSagaRepository.findByTransactionId(UUID.fromString(transactionId))
                .map(state -> ResponseEntity.ok(
                        historyRepository.findBySagaIdOrderByOccurredAtAsc(state.getSagaId())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long activeTransfers = transferSagaRepository.countByCurrentStepIn(
                List.of(TransferStep.BALANCE_RESERVING, TransferStep.FRAUD_CHECKING,
                        TransferStep.LEDGER_POSTING, TransferStep.BALANCE_FINALIZING,
                        TransferStep.NOTIFICATION_SENDING));
        long activeOnboarding = onboardingRepository
                .findByCurrentStep(com.nexus.saga.domain.model.onboarding.OnboardingStep.KYC_INITIATED)
                .size();

        return ResponseEntity.ok(Map.of(
                "activeTransferSagas", activeTransfers,
                "activeOnboardingSagas", activeOnboarding,
                "status", "OPERATIONAL"));
    }

    @GetMapping("/stuck")
    public ResponseEntity<?> getStuckSagas() {
        var stuckTransfers = transferSagaRepository.findByCurrentStepNotInAndExpiresAtBefore(
                List.of(TransferStep.COMPLETED, TransferStep.COMPENSATION_COMPLETED,
                        TransferStep.PERMANENTLY_FAILED),
                java.time.Instant.now());
        return ResponseEntity.ok(Map.of("stuckTransferSagas", stuckTransfers));
    }
}