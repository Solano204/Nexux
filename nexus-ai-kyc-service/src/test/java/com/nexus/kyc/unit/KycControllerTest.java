package com.nexus.kyc.unit;

import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationDecision;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import com.nexus.kyc.web.controller.KycController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycControllerTest {

    @Mock private KycVerificationService verificationService;
    @Mock private KycDocumentRepository kycDocumentRepository;

    private KycController controller;

    @BeforeEach
    void setUp() {
        controller = new KycController(verificationService, kycDocumentRepository);
    }

    private MockMultipartFile document() {
        return new MockMultipartFile("document", "id.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    void verifyReturnsApprovedResultOmittingInternalFields() throws Exception {
        KycDocumentMongoDB doc = KycDocumentMongoDB.builder()
                .verificationId("v-1").userId("user-1").status(KycStatus.APPROVED)
                .decision(new KycVerificationDecision(KycStatus.APPROVED, 0.97, Map.of(),
                        java.util.List.of(), null, true, 0,
                        "Jane Doe", "1990-01-01", "AB123456", "MX",
                        "internal reasoning that must not leak", false, null))
                .build();
        when(verificationService.verify(any(), any(), anyString(), anyString())).thenReturn(doc);

        ResponseEntity<KycController.KycVerificationResult> response = controller.verify(
                document(), "Jane Doe", "1990-01-01", "AB123456", "PASSPORT",
                "MX", "es", "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("APPROVED");
        assertThat(response.getBody().verificationId()).isEqualTo("v-1");
    }

    @Test
    void verifyReturns400ForInvalidDocumentType() throws Exception {
        ResponseEntity<KycController.KycVerificationResult> response = controller.verify(
                document(), "Jane Doe", "1990-01-01", "AB123456", "NOT_A_REAL_TYPE",
                null, "es", "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo("REJECTED");
        verifyNoInteractions(verificationService);
    }

    @Test
    void verifyReturns500BodyWithoutThrowingWhenPipelineFails() {
        when(verificationService.verify(any(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("openai down"));

        ResponseEntity<KycController.KycVerificationResult> response = controller.verify(
                document(), "Jane Doe", "1990-01-01", "AB123456", "PASSPORT",
                null, "es", "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().userFacingMessage()).doesNotContain("openai down");
    }

    @Test
    void verifyDefaultsLanguageToSpanishWhenOmitted() {
        KycDocumentMongoDB doc = KycDocumentMongoDB.builder()
                .verificationId("v-1").userId("user-1").status(KycStatus.REVIEW_REQUIRED).build();
        when(verificationService.verify(any(), any(), anyString(), anyString())).thenReturn(doc);

        controller.verify(document(), "Jane Doe", "1990-01-01", "AB123456", "PASSPORT",
                null, null, "user-1");

        var captor = org.mockito.ArgumentCaptor.forClass(com.nexus.kyc.domain.model.KycVerificationRequest.class);
        verify(verificationService).verify(captor.capture(), any(), anyString(), anyString());
        assertThat(captor.getValue().language()).isEqualTo("es");
    }

    @Test
    void getStatusReturns404WhenNoMatchForUser() {
        when(kycDocumentRepository.findByVerificationIdAndUserId("v-1", "user-1"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getStatus("v-1", "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getStatusReturnsReviewRequiredFlagCorrectly() {
        KycDocumentMongoDB doc = KycDocumentMongoDB.builder()
                .verificationId("v-1").userId("user-1").status(KycStatus.REVIEW_REQUIRED)
                .submittedAt(Instant.now()).build();
        when(kycDocumentRepository.findByVerificationIdAndUserId("v-1", "user-1"))
                .thenReturn(Optional.of(doc));

        ResponseEntity<?> response = controller.getStatus("v-1", "user-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
