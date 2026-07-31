package com.nexus.risk.unit;

import com.nexus.risk.agent.model.RiskScoringAgent;
import com.nexus.risk.application.batch.NightlyRiskScoringJobTriggerService;
import com.nexus.risk.domain.model.RiskProfile;
import com.nexus.risk.domain.model.enums.RiskTier;
import com.nexus.risk.infrastructure.jpa.RiskProfileJpaEntity;
import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import com.nexus.risk.infrastructure.redis.RiskProfileCacheService;
import com.nexus.risk.web.controller.InternalRiskController;
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
class InternalRiskControllerTest {

    @Mock private RiskProfileRepository profileRepository;
    @Mock private RiskProfileCacheService cacheService;
    @Mock private RiskScoringAgent riskScoringAgent;
    @Mock private NightlyRiskScoringJobTriggerService triggerService;

    private InternalRiskController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalRiskController(profileRepository, cacheService, riskScoringAgent, triggerService);
    }

    @Test
    void getCurrentProfileReturns404WhenNoneExists() {
        when(profileRepository.findLatestByUserId("user-1")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getCurrentProfile("user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getRiskTierPrefersRedisCacheOverPostgres() {
        when(cacheService.getRiskTier("user-1")).thenReturn("HIGH");

        ResponseEntity<Map<String, String>> response = controller.getRiskTier("user-1");

        assertThat(response.getBody().get("riskTier")).isEqualTo("HIGH");
        verifyNoInteractions(profileRepository);
    }

    @Test
    void getRiskTierFallsBackToPostgresOnCacheMiss() {
        when(cacheService.getRiskTier("user-1")).thenReturn(null);
        RiskProfileJpaEntity entity = mock(RiskProfileJpaEntity.class);
        when(entity.getRiskTier()).thenReturn("MEDIUM");
        when(profileRepository.findLatestByUserId("user-1")).thenReturn(Optional.of(entity));

        ResponseEntity<Map<String, String>> response = controller.getRiskTier("user-1");

        assertThat(response.getBody().get("riskTier")).isEqualTo("MEDIUM");
    }

    @Test
    void getRiskTierReturnsUnknownWhenNoProfileExistsAnywhere() {
        when(cacheService.getRiskTier("user-1")).thenReturn(null);
        when(profileRepository.findLatestByUserId("user-1")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.getRiskTier("user-1");

        assertThat(response.getBody().get("riskTier")).isEqualTo("UNKNOWN");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void triggerComputationReturnsComputedProfileSummary() {
        UUID userId = UUID.randomUUID();
        RiskProfile profile = new RiskProfile("profile-1", userId.toString(), Instant.now(),
                Instant.now().plusSeconds(86400), 1, 75, RiskTier.HIGH, 0.9,
                null, null, null, null, null, "gpt-4o", "summary",
                List.of("velocity_tool"), List.of(), List.of(), "STANDARD");
        when(riskScoringAgent.computeRiskProfile(userId.toString(), "MANUAL")).thenReturn(profile);

        ResponseEntity<?> response = controller.triggerComputation(userId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("riskTier")).isEqualTo("HIGH");
        assertThat(body.get("overallRiskScore")).isEqualTo(75);
    }

    @Test
    void triggerComputationReturns500OnAgentFailureWithoutThrowing() {
        when(riskScoringAgent.computeRiskProfile(anyString(), anyString()))
                .thenThrow(new RuntimeException("OpenAI timeout"));

        ResponseEntity<?> response = controller.triggerComputation("user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("status")).isEqualTo("FAILED");
    }

    @Test
    void triggerBatchDelegatesToTriggerService() {
        when(triggerService.triggerManualBatch()).thenReturn(Map.of("status", "STARTED"));

        ResponseEntity<Map<String, Object>> response = controller.triggerBatch();

        assertThat(response.getBody().get("status")).isEqualTo("STARTED");
    }

    @Test
    void getStatsAggregatesCountsPerTier() {
        when(profileRepository.countByRiskTier("VERY_LOW")).thenReturn(10L);
        when(profileRepository.countByRiskTier("LOW")).thenReturn(20L);
        when(profileRepository.countByRiskTier("MEDIUM")).thenReturn(30L);
        when(profileRepository.countByRiskTier("HIGH")).thenReturn(5L);
        when(profileRepository.countByRiskTier("VERY_HIGH")).thenReturn(1L);
        when(triggerService.getRecomputationCandidates()).thenReturn(List.of("user-a", "user-b"));

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertThat(response.getBody().get("medium")).isEqualTo(30L);
        assertThat(response.getBody().get("candidatesForRecomputation")).isEqualTo(2);
    }
}
