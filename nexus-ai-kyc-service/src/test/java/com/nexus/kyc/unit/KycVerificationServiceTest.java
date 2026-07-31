package com.nexus.kyc.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.application.pipeline.Stage1DocumentExtraction;
import com.nexus.kyc.application.pipeline.Stage2DataComparison;
import com.nexus.kyc.application.validation.DocumentQualityValidator;
import com.nexus.kyc.application.validation.HardRuleValidator;
import com.nexus.kyc.domain.exception.DocumentQualityException;
import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.KycVerificationDecision;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import com.nexus.kyc.infrastructure.jpa.KycAuditRepository;
import com.nexus.kyc.infrastructure.kafka.KycResultOutboxPublisher;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycVerificationServiceTest {

    @Mock private Stage1DocumentExtraction stage1;
    @Mock private Stage2DataComparison stage2;
    @Mock private HardRuleValidator hardRuleValidator;
    @Mock private DocumentQualityValidator qualityValidator;
    @Mock private KycDocumentRepository kycDocumentRepository;
    @Mock private KycAuditRepository auditRepository;
    @Mock private KycResultOutboxPublisher resultPublisher;

    private KycVerificationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // persistAuditEntry() does UUID.fromString(doc.getUserId()) - a
    // human-readable id like USER_ID isn't valid input for this system.
    private static final String USER_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        service = new KycVerificationService(stage1, stage2, hardRuleValidator, qualityValidator,
                kycDocumentRepository, auditRepository, resultPublisher, ObservationRegistry.NOOP,
                new SimpleMeterRegistry(), objectMapper, ThreadPoolBulkheadRegistry.ofDefaults());
        when(kycDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private KycVerificationRequest request() {
        return new KycVerificationRequest(USER_ID, "Jane Doe", "1990-01-01",
                "AB123456", DocumentType.PASSPORT, "MX", "es");
    }

    @Test
    void rejectsOnFailedDocumentQualityWithoutCallingStage1() {
        when(qualityValidator.validate(any(), anyString()))
                .thenReturn(new DocumentQualityValidator.QualityCheckResult(false, List.of("FILE_EMPTY")));

        KycDocumentMongoDB result = service.doVerify(request(), new byte[]{1, 2, 3}, "image/jpeg", "saga-1");

        assertThat(result.getStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(result.isPassedHardRules()).isFalse();
        verifyNoInteractions(stage1, stage2, hardRuleValidator);
        verify(resultPublisher).publishResult(eq(USER_ID), anyString(), any());
    }

    @Test
    void rejectsOnHardRuleFailureWithoutCallingStage1() {
        when(qualityValidator.validate(any(), anyString()))
                .thenReturn(new DocumentQualityValidator.QualityCheckResult(true, List.of()));
        when(kycDocumentRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(hardRuleValidator.validate(any(), eq(0)))
                .thenReturn(new HardRuleValidator.HardRuleResult(false, List.of("UNDERAGE")));

        KycDocumentMongoDB result = service.doVerify(request(), new byte[]{1, 2, 3}, "image/jpeg", "saga-1");

        assertThat(result.getStatus()).isEqualTo(KycStatus.REJECTED);
        verifyNoInteractions(stage1, stage2);
    }

    @Test
    void rejectsWhenStage1ThrowsDocumentQualityException() {
        when(qualityValidator.validate(any(), anyString()))
                .thenReturn(new DocumentQualityValidator.QualityCheckResult(true, List.of()));
        when(kycDocumentRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(hardRuleValidator.validate(any(), eq(0)))
                .thenReturn(new HardRuleValidator.HardRuleResult(true, List.of()));
        when(stage1.extract(any(), anyString(), anyString()))
                .thenThrow(new DocumentQualityException("blurry", List.of(RejectionReason.DOCUMENT_UNREADABLE)));

        KycDocumentMongoDB result = service.doVerify(request(), new byte[]{1, 2, 3}, "image/jpeg", "saga-1");

        assertThat(result.getStatus()).isEqualTo(KycStatus.REJECTED);
        verifyNoInteractions(stage2);
    }

    @Test
    void approvesHappyPathAndPersistsAuditEntry() {
        when(qualityValidator.validate(any(), anyString()))
                .thenReturn(new DocumentQualityValidator.QualityCheckResult(true, List.of()));
        when(kycDocumentRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(hardRuleValidator.validate(any(), eq(0)))
                .thenReturn(new HardRuleValidator.HardRuleResult(true, List.of()));

        KycExtractedData extracted = new KycExtractedData(
                DocumentType.PASSPORT, "MX", "AB123456",
                "Jane Doe", "Jane", "Doe", "01/01/1990", "01/01/2030", "MX", "F", null,
                null, null, false, false,
                0.95, 0.9, 0.9, List.of(),
                false, false, null,
                List.of(), "clear document");
        when(stage1.extract(any(), anyString(), anyString())).thenReturn(extracted);

        KycVerificationDecision decision = new KycVerificationDecision(
                KycStatus.APPROVED, 0.97, Map.of(),
                List.of(), null, true, 0,
                "Jane Doe", "1990-01-01", "AB123456", "MX",
                "all fields match", false, null);
        when(stage2.compare(any(), any())).thenReturn(decision);

        KycDocumentMongoDB result = service.doVerify(request(), new byte[]{1, 2, 3}, "image/jpeg", "saga-1");

        assertThat(result.getStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(result.getAttemptNumber()).isEqualTo(1);
        verify(auditRepository).save(any());
        verify(resultPublisher).publishResult(eq(USER_ID), anyString(), any());
    }

    @Test
    void incrementsAttemptNumberBasedOnPreviousAttempts() {
        when(qualityValidator.validate(any(), anyString()))
                .thenReturn(new DocumentQualityValidator.QualityCheckResult(true, List.of()));
        when(kycDocumentRepository.countByUserId(USER_ID)).thenReturn(2L);
        when(hardRuleValidator.validate(any(), eq(2)))
                .thenReturn(new HardRuleValidator.HardRuleResult(false, List.of("MAX_ATTEMPTS_EXCEEDED")));

        KycDocumentMongoDB result = service.doVerify(request(), new byte[]{1, 2, 3}, "image/jpeg", "saga-1");

        assertThat(result.getAttemptNumber()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo(KycStatus.REJECTED);
    }
}
