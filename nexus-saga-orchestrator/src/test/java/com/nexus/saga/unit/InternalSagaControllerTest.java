package com.nexus.saga.unit;

import com.nexus.saga.domain.model.onboarding.OnboardingFlowSagaState;
import com.nexus.saga.domain.model.onboarding.OnboardingStep;
import com.nexus.saga.domain.model.transfer.TransferSagaState;
import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.OnboardingSagaRepository;
import com.nexus.saga.infrastructure.jpa.SagaStepHistoryRepository;
import com.nexus.saga.infrastructure.jpa.TransferSagaRepository;
import com.nexus.saga.web.controller.InternalSagaController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalSagaControllerTest {

    @Mock private TransferSagaRepository transferSagaRepository;
    @Mock private OnboardingSagaRepository onboardingRepository;
    @Mock private SagaStepHistoryRepository historyRepository;

    private InternalSagaController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalSagaController(transferSagaRepository, onboardingRepository, historyRepository);
    }

    @Test
    void getTransferSagaReturns404WhenMissing() {
        UUID txnId = UUID.randomUUID();
        when(transferSagaRepository.findByTransactionId(txnId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getTransferSaga(txnId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTransferSagaReturns200WhenFound() {
        UUID txnId = UUID.randomUUID();
        TransferSagaState state = TransferSagaState.builder()
                .sagaId(UUID.randomUUID()).transactionId(txnId)
                .currentStep(TransferStep.LEDGER_POSTING).build();
        when(transferSagaRepository.findByTransactionId(txnId)).thenReturn(Optional.of(state));

        ResponseEntity<?> response = controller.getTransferSaga(txnId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(state);
    }

    @Test
    void getOnboardingSagaReturns404WhenMissing() {
        UUID userId = UUID.randomUUID();
        when(onboardingRepository.findByUserId(userId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getOnboardingSaga(userId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getOnboardingSagaReturns200WhenFound() {
        UUID userId = UUID.randomUUID();
        OnboardingFlowSagaState state = OnboardingFlowSagaState.builder()
                .sagaId(UUID.randomUUID()).userId(userId)
                .currentStep(OnboardingStep.ACCOUNTS_CREATING)
                .startedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        when(onboardingRepository.findByUserId(userId)).thenReturn(Optional.of(state));

        ResponseEntity<?> response = controller.getOnboardingSaga(userId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getTransferHistoryReturns404WhenSagaMissing() {
        UUID txnId = UUID.randomUUID();
        when(transferSagaRepository.findByTransactionId(txnId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getTransferHistory(txnId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void getTransferHistoryReturnsChronologicalHistory() {
        UUID txnId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        TransferSagaState state = TransferSagaState.builder()
                .sagaId(sagaId).transactionId(txnId).currentStep(TransferStep.COMPLETED).build();
        when(transferSagaRepository.findByTransactionId(txnId)).thenReturn(Optional.of(state));
        when(historyRepository.findBySagaIdOrderByOccurredAtAsc(sagaId)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getTransferHistory(txnId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(historyRepository).findBySagaIdOrderByOccurredAtAsc(sagaId);
    }

    @Test
    void getStatsAggregatesActiveSagaCounts() {
        when(transferSagaRepository.countByCurrentStepIn(anyList())).thenReturn(5L);
        when(onboardingRepository.findByCurrentStep(OnboardingStep.KYC_INITIATED))
                .thenReturn(List.of(mock(OnboardingFlowSagaState.class), mock(OnboardingFlowSagaState.class)));

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertThat(response.getBody().get("activeTransferSagas")).isEqualTo(5L);
        assertThat(response.getBody().get("activeOnboardingSagas")).isEqualTo(2L);
        assertThat(response.getBody().get("status")).isEqualTo("OPERATIONAL");
    }

    @Test
    void getStuckSagasReturnsExpiredNonTerminalSagas() {
        when(transferSagaRepository.findByCurrentStepNotInAndExpiresAtBefore(anyList(), any()))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getStuckSagas();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
