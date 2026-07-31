package com.nexus.identity.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.identity.infrastructure.ai.KycRejectionExplainer;
import com.nexus.identity.infrastructure.aws.S3DocumentUploader;
import com.nexus.identity.infrastructure.aws.SqsKycPublisher;
import com.nexus.identity.infrastructure.persistence.KycVerificationRepository;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.infrastructure.persistence.UserRepository;
import com.nexus.identity.web.dto.request.KycResultRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * KYC Flow Integration Test.
 *
 * Tests the complete KYC initiation -> result processing pipeline.
 * AWS services (S3, SQS) are mocked - test focuses on domain logic.
 *
 * Result processing is exercised by calling
 * UserCommandService.processKycResult(...) directly, not through the
 * old POST /internal/v1/users/{userId}/kyc/result endpoint - that
 * endpoint no longer exists, replaced by KycResultConsumer (Kafka,
 * topic identity.kyc.result). The consumer is a thin translation
 * layer that calls this same commandService method, so calling it
 * directly here tests the real domain logic without needing a
 * Testcontainers Kafka broker just to publish one message.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.bus.enabled=false",
                "eureka.client.enabled=false"
        })
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class KycFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nexus_identity_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "test-password");

    // Mock AWS clients — no real AWS needed for KYC flow tests
    @MockitoBean
    private S3DocumentUploader s3Uploader;

    @MockitoBean
    private SqsKycPublisher sqsPublisher;

    // Real KycRejectionExplainer calls OpenAI (Spring AI ChatClient) - not
    // mocking it would make kycResult_rejected_keepsUserRejected a flaky
    // test dependent on a real external service and a valid API key,
    // exactly the flakiness cause this platform's own testing strategy
    // (Fase 6) calls out. Mocked here, not because rejection-explanation
    // quality doesn't matter, but because that's KycRejectionExplainer's
    // own concern to test in isolation, not this integration test's.
    @MockitoBean
    private KycRejectionExplainer rejectionExplainer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KycVerificationRepository kycRepository;

    @Autowired
    private UserCommandService commandService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Test
    @DisplayName("KYC initiation: creates verification record in DB")
    void kycInitiate_validDocument_createsVerificationRecord()
            throws Exception {

        // Setup: register a user first
        UUID userId = registerTestUser("kyc-test@nexusbank.com",
                "+525512340001");

        // Mock S3 upload and SQS publish
        when(s3Uploader.uploadKycDocument(any(), any(), anyString(), any()))
                .thenReturn("kyc/" + userId + "/test-verification/passport.jpg");

        // Setup document file
        MockMultipartFile document = new MockMultipartFile(
                "document", "passport.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "fake-image-bytes".getBytes());

        // Initiate KYC (authenticated with X-User-Id header — gateway sets this)
        mockMvc.perform(multipart("/api/v1/users/me/kyc/initiate")
                        .file(document)
                        .param("documentType", "PASSPORT")
                        .param("fullName", "Jane Doe")
                        .param("dateOfBirth", "1990-01-01")
                        .param("documentNumber", "X123")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.verificationId").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // Verify DB record was created
        var verifications = kycRepository
                .findByUserIdOrderByInitiatedAtDesc(userId);
        assertThat(verifications).hasSize(1);
        assertThat(verifications.get(0).getDocumentType())
                .isEqualTo("PASSPORT");
        assertThat(verifications.get(0).getFinalDecision().name())
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("KYC result: APPROVED changes user status to ACTIVE")
    void kycResult_approved_activatesUserAccount() throws Exception {
        // Register user
        UUID userId = registerTestUser("kyc-approved@nexusbank.com",
                "+525512340002");

        // Mock S3 + SQS
        String verificationId = UUID.randomUUID().toString();
        when(s3Uploader.uploadKycDocument(any(), any(), anyString(), any()))
                .thenReturn("kyc/test-path.jpg");

        // Initiate KYC
        MockMultipartFile document = new MockMultipartFile(
                "document", "id.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                "fake-image".getBytes());

        String initResponse = mockMvc.perform(
                        multipart("/api/v1/users/me/kyc/initiate")
                                .file(document)
                                .param("documentType", "NATIONAL_ID")
                                .param("fullName", "Jane Doe")
                                .param("dateOfBirth", "1990-01-01")
                                .param("documentNumber", "X123")
                                .header("X-User-Id", userId.toString()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String createdVerificationId = objectMapper
                .readTree(initResponse)
                .get("verificationId").asText();

        // KYC result, as KycResultConsumer would deliver it after consuming
        // from Kafka topic identity.kyc.result.
        KycResultRequest result = new KycResultRequest(
                createdVerificationId,
                true,         // approved
                Map.of("name", "Test User", "dob", "1990-01-15"),
                Map.of("match", true, "confidence", 0.98),
                List.of()
        );

        commandService.processKycResult(
                userId, UUID.fromString(createdVerificationId), result, "test-trace");

        // Verify user is now ACTIVE
        var user = userRepository.findByUserIdAndDeletedAtIsNull(userId);
        assertThat(user).isPresent();
        assertThat(user.get().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(user.get().getKycVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("KYC result: REJECTED keeps user in KYC_REJECTED status")
    void kycResult_rejected_keepsUserRejected() throws Exception {
        UUID userId = registerTestUser("kyc-rejected@nexusbank.com",
                "+525512340003");

        when(s3Uploader.uploadKycDocument(any(), any(), anyString(), any()))
                .thenReturn("kyc/test-path.jpg");

        MockMultipartFile document = new MockMultipartFile(
                "document", "passport.jpg",
                MediaType.IMAGE_JPEG_VALUE, "fake".getBytes());

        String initResponse = mockMvc.perform(
                        multipart("/api/v1/users/me/kyc/initiate")
                                .file(document)
                                .param("documentType", "PASSPORT")
                                .param("fullName", "Jane Doe")
                                .param("dateOfBirth", "1990-01-01")
                                .param("documentNumber", "X123")
                                .header("X-User-Id", userId.toString()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String verificationId = objectMapper.readTree(initResponse)
                .get("verificationId").asText();

        when(rejectionExplainer.explain(any(), anyString()))
                .thenReturn("Tu documento no pudo verificarse. Intenta de nuevo con mejor iluminacion.");

        KycResultRequest result = new KycResultRequest(
                verificationId,
                false,
                Map.of(),
                Map.of("match", false),
                List.of("FACE_BLURRY", "DOCUMENT_PARTIALLY_OBSCURED")
        );

        commandService.processKycResult(
                userId, UUID.fromString(verificationId), result, "test-trace");

        var user = userRepository.findByUserIdAndDeletedAtIsNull(userId);
        assertThat(user).isPresent();
        assertThat(user.get().getStatus().name()).isEqualTo("KYC_REJECTED");
    }

    // ── Helpers ───────────────────────────────────────────────

    private UUID registerTestUser(String email, String phone)
            throws Exception {
        Map<String, Object> body = Map.of(
                "email", email,
                "password", "SecurePassword123!",
                "fullName", "KYC Test User",
                "phoneNumber", phone,
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(
                objectMapper.readTree(response).get("userId").asText());
    }
}
