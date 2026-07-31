package com.nexus.fraud.unit;

import com.nexus.fraud.application.FraudAnalysisService;
import com.nexus.fraud.domain.model.FraudDecisionEntity;
import com.nexus.fraud.infrastructure.persistence.FraudDecisionRepository;
import com.nexus.fraud.infrastructure.redis.FraudRedisRepository;
import com.nexus.fraud.web.controller.InternalFraudController;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalFraudControllerTest {

    @Mock private FraudAnalysisService fraudAnalysisService;
    @Mock private FraudDecisionRepository decisionRepository;
    @Mock private FraudRedisRepository redisRepository;

    private InternalFraudController controller;
    private static final UUID DECISION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new InternalFraudController(
                fraudAnalysisService, decisionRepository, redisRepository, ObservationRegistry.NOOP);
    }

    private FraudDecisionEntity decisionEntity() {
        return FraudDecisionEntity.builder()
                .decisionId(DECISION_ID)
                .transactionId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .sourceAccountId(UUID.randomUUID())
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .decisionOutcome("REVIEW")
                .riskScore(new BigDecimal("65.0"))
                .createdAt(Instant.now())
                .sarFiled(false)
                .build();
    }

    @Test
    void getDecisionReturns404WhenMissing() {
        UUID txnId = UUID.randomUUID();
        when(decisionRepository.findByTransactionId(txnId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getDecision(txnId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getDecisionReturns200WithMappedResponse() {
        UUID txnId = UUID.randomUUID();
        FraudDecisionEntity entity = decisionEntity();
        when(decisionRepository.findByTransactionId(txnId)).thenReturn(Optional.of(entity));

        ResponseEntity<?> response = controller.getDecision(txnId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (InternalFraudController.FraudDecisionResponse) response.getBody();
        assertThat(body.decisionId()).isEqualTo(DECISION_ID.toString());
    }

    @Test
    void getUserDecisionsReturnsPagedResults() {
        UUID userId = UUID.randomUUID();
        Pageable pageable = Pageable.unpaged();
        Page<FraudDecisionEntity> page = new PageImpl<>(List.of(decisionEntity()));
        when(decisionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)).thenReturn(page);

        ResponseEntity<Page<InternalFraudController.FraudDecisionResponse>> response =
                controller.getUserDecisions(userId, pageable);

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    void getPendingReviewsReturnsListFromRepository() {
        when(decisionRepository.findPendingReviews()).thenReturn(List.of(decisionEntity()));

        var response = controller.getPendingReviews();

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void blacklistMerchantCallsRedisAndReturnsConfirmation() {
        ResponseEntity<Map<String, Object>> response =
                controller.blacklistMerchant("merchant-1", "operator-1");

        verify(redisRepository).blacklistMerchant("merchant-1");
        assertThat(response.getBody().get("action")).isEqualTo("BLACKLISTED");
        assertThat(response.getBody().get("blacklistedBy")).isEqualTo("operator-1");
    }

    @Test
    void blacklistMerchantDefaultsOperatorToSystemWhenHeaderMissing() {
        ResponseEntity<Map<String, Object>> response =
                controller.blacklistMerchant("merchant-1", null);

        assertThat(response.getBody().get("blacklistedBy")).isEqualTo("SYSTEM");
    }

    @Test
    void unblacklistMerchantCallsRedis() {
        controller.unblacklistMerchant("merchant-1", "operator-1");

        verify(redisRepository).removeFromBlacklist("merchant-1");
    }

    @Test
    void recordReviewOutcomeReturns409WhenAlreadyReviewed() {
        when(decisionRepository.recordReviewOutcome(any(), any(), anyString(), any(), any())).thenReturn(0);
        var request = new InternalFraudController.ReviewOutcomeRequest(UUID.randomUUID(), "CLEARED", "notes");

        ResponseEntity<?> response = controller.recordReviewOutcome(DECISION_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void recordReviewOutcomeFlagsSourceAccountOnConfirmedFraud() {
        when(decisionRepository.recordReviewOutcome(any(), any(), anyString(), any(), any())).thenReturn(1);
        FraudDecisionEntity entity = decisionEntity();
        when(decisionRepository.findById(DECISION_ID)).thenReturn(Optional.of(entity));
        var request = new InternalFraudController.ReviewOutcomeRequest(UUID.randomUUID(), "CONFIRMED_FRAUD", "fraud confirmed");

        ResponseEntity<?> response = controller.recordReviewOutcome(DECISION_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(redisRepository).flagAccount(entity.getSourceAccountId().toString());
    }

    @Test
    void recordReviewOutcomeDoesNotFlagAccountWhenCleared() {
        when(decisionRepository.recordReviewOutcome(any(), any(), anyString(), any(), any())).thenReturn(1);
        var request = new InternalFraudController.ReviewOutcomeRequest(UUID.randomUUID(), "CLEARED", "false positive");

        controller.recordReviewOutcome(DECISION_ID, request);

        verify(redisRepository, never()).flagAccount(anyString());
    }

    @Test
    void recordSarFilingReturns404WhenDecisionMissing() {
        when(decisionRepository.recordSarFiling(any(), any(), anyString())).thenReturn(0);
        var request = new InternalFraudController.SarFilingRequest("SAR-2026-001");

        ResponseEntity<?> response = controller.recordSarFiling(DECISION_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recordSarFilingReturns200OnSuccess() {
        when(decisionRepository.recordSarFiling(any(), any(), anyString())).thenReturn(1);
        var request = new InternalFraudController.SarFilingRequest("SAR-2026-001");

        ResponseEntity<?> response = controller.recordSarFiling(DECISION_ID, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getFraudMetricsComputesRejectionRate() {
        when(decisionRepository.countByDecisionOutcomeAndCreatedAtAfter(eq("APPROVE"), any())).thenReturn(70L);
        when(decisionRepository.countByDecisionOutcomeAndCreatedAtAfter(eq("REJECT"), any())).thenReturn(20L);
        when(decisionRepository.countByDecisionOutcomeAndCreatedAtAfter(eq("REVIEW"), any())).thenReturn(10L);
        when(decisionRepository.findPendingReviews()).thenReturn(List.of());
        when(decisionRepository.findSarFiled()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getFraudMetrics();

        @SuppressWarnings("unchecked")
        Map<String, Object> lastHour = (Map<String, Object>) response.getBody().get("lastHour");
        assertThat(lastHour.get("total")).isEqualTo(100L);
        assertThat(lastHour.get("rejectionRatePercent")).isEqualTo(new BigDecimal("20.00"));
    }

    @Test
    void getFraudMetricsHandlesZeroTotalWithoutDivideByZero() {
        when(decisionRepository.countByDecisionOutcomeAndCreatedAtAfter(anyString(), any())).thenReturn(0L);
        when(decisionRepository.findPendingReviews()).thenReturn(List.of());
        when(decisionRepository.findSarFiled()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getFraudMetrics();

        @SuppressWarnings("unchecked")
        Map<String, Object> lastHour = (Map<String, Object>) response.getBody().get("lastHour");
        assertThat(lastHour.get("rejectionRatePercent")).isEqualTo(BigDecimal.ZERO.setScale(2));
    }

    @Test
    void analyzeTransactionReturns500WithProblemDetailOnFailure() {
        var request = new com.nexus.fraud.web.dto.FraudAnalysisRequest(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), null, new BigDecimal("100.00"), "MXN", "TRANSFER",
                "test payment", null, null, null, "1.2.3.4", "device-1", true, 400, 12,
                Map.of(), "trace-1");
        when(fraudAnalysisService.analyze(request)).thenThrow(new RuntimeException("LLM timeout"));

        ResponseEntity<?> response = controller.analyzeTransaction(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
