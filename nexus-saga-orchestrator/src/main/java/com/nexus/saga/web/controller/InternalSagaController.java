package com.nexus.saga.web.controller;

import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/internal/v1/sagas")
@RequiredArgsConstructor
@Tag(name = "Sagas (Internal)", description = "Read-only saga state introspection — the real transfer/onboarding coordination is 100% Kafka-driven, not exposed here.")
@SecurityRequirement(name = "X-Internal-Service")
public class InternalSagaController {

    private final TransferSagaRepository transferSagaRepository;
    private final OnboardingSagaRepository onboardingRepository;
    private final SagaStepHistoryRepository historyRepository;

    @Operation(summary = "Get transfer saga state", description = "Current step, whether it's active/completed/compensated — for one specific transaction.")
    @ApiResponse(responseCode = "200", description = "Saga state retrieved")
    @ApiResponse(responseCode = "404", description = "No saga for this transaction ID")
    @GetMapping("/transfer/{transactionId}")
    public ResponseEntity<?> getTransferSaga(
            @Parameter(description = "Transaction UUID", required = true)
            @PathVariable String transactionId) {
        return transferSagaRepository.findByTransactionId(UUID.fromString(transactionId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get onboarding saga state", description = "Current KYC/onboarding step for one user.")
    @ApiResponse(responseCode = "200", description = "Saga state retrieved")
    @ApiResponse(responseCode = "404", description = "No onboarding saga for this user")
    @GetMapping("/onboarding/{userId}")
    public ResponseEntity<?> getOnboardingSaga(
            @Parameter(description = "User UUID", required = true)
            @PathVariable String userId) {
        return onboardingRepository.findByUserId(UUID.fromString(userId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get transfer saga step history", description = "Chronological step-by-step history for one transaction's saga.")
    @ApiResponse(responseCode = "200", description = "History retrieved")
    @ApiResponse(responseCode = "404", description = "No saga for this transaction ID")
    @GetMapping("/transfer/{transactionId}/history")
    public ResponseEntity<?> getTransferHistory(
            @Parameter(description = "Transaction UUID", required = true)
            @PathVariable String transactionId) {
        return transferSagaRepository.findByTransactionId(UUID.fromString(transactionId))
                .map(state -> ResponseEntity.ok(
                        historyRepository.findBySagaIdOrderByOccurredAtAsc(state.getSagaId())))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get active saga counts", description = "How many transfer/onboarding sagas are currently in-flight (not yet completed/failed).")
    @ApiResponse(responseCode = "200", description = "Stats retrieved")
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

    @Operation(summary = "Get stuck transfer sagas", description = "Non-terminal sagas past their expiry — candidates for manual investigation or forced compensation.")
    @ApiResponse(responseCode = "200", description = "Stuck sagas retrieved (empty list if none)")
    @GetMapping("/stuck")
    public ResponseEntity<?> getStuckSagas() {
        var stuckTransfers = transferSagaRepository.findByCurrentStepNotInAndExpiresAtBefore(
                List.of(TransferStep.COMPLETED, TransferStep.COMPENSATION_COMPLETED,
                        TransferStep.PERMANENTLY_FAILED),
                java.time.Instant.now());
        return ResponseEntity.ok(Map.of("stuckTransferSagas", stuckTransfers));
    }
}