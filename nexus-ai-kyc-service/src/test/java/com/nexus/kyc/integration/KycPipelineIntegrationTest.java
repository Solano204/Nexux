package com.nexus.kyc.integration;

import com.nexus.kyc.application.pipeline.Stage1DocumentExtraction;
import com.nexus.kyc.application.pipeline.Stage2DataComparison;
import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.KycVerificationDecision;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.jpa.KycAuditRepository;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end KYC pipeline test: a real Kafka message on "identity.kyc"
 * drives KycInitiationConsumer -> S3 download (LocalStack) ->
 * KycVerificationService.verify() (real ThreadPoolBulkhead + self-proxy,
 * only exercised correctly inside a real Spring context) -> real MongoDB
 * persistence + real Postgres audit trail. Stage1/Stage2 (real OpenAI
 * vision/comparison calls) are mocked — same reasoning as every other
 * "integration but not against a paid LLM" test in this platform.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class KycPipelineIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("nexus_ai_kyc_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @Container
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.S3);

    private static final String BUCKET = "nexus-kyc-documents";

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired KycDocumentRepository kycDocumentRepository;
    @Autowired KycAuditRepository kycAuditRepository;

    @MockitoBean Stage1DocumentExtraction stage1;
    @MockitoBean Stage2DataComparison stage2;

    // The KycInitiationConsumer's @KafkaListener consumer group
    // (nexus-ai-kyc-service) subscribes to "identity.kyc" as soon as the
    // context starts, before this test ever publishes to it - relying on
    // auto-create-on-subscribe here was observed to never resolve past the
    // initial LEADER_NOT_AVAILABLE metadata response (no partitions-assigned
    // log ever follows, unlike the springCloudBus consumer group in the same
    // context), so create the topic explicitly up front instead of racing it.
    @BeforeAll
    static void createTopics() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("identity.kyc", 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to pre-create identity.kyc topic", e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("nexus.aws.endpoint-override",
                () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("nexus.aws.access-key-id", localstack::getAccessKey);
        registry.add("nexus.aws.secret-access-key", localstack::getSecretKey);
        registry.add("nexus.aws.region", localstack::getRegion);
        registry.add("nexus.kyc.s3.bucket", () -> BUCKET);
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    private String uploadTestDocument() throws Exception {
        S3Client s3 = S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true)
                .build();

        s3.createBucket(b -> b.bucket(BUCKET));
        String key = "kyc/" + UUID.randomUUID() + "/passport.jpg";
        // Minimal valid JPEG magic-byte payload — Stage1 is mocked so
        // content correctness beyond "downloadable bytes" doesn't matter.
        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03};
        s3.putObject(b -> b.bucket(BUCKET).key(key), software.amazon.awssdk.core.sync.RequestBody.fromBytes(fakeJpeg));
        return key;
    }

    private void publishKycInitiated(String userId, String verificationId, String s3Key) throws Exception {
        String event = """
                {
                  "userId": "%s",
                  "verificationId": "%s",
                  "s3Path": "%s",
                  "documentType": "PASSPORT",
                  "fullName": "Jane Doe",
                  "dateOfBirth": "1990-01-01",
                  "documentNumber": "AB123456",
                  "nationality": "MX",
                  "language": "es",
                  "mimeType": "image/jpeg"
                }
                """.formatted(userId, verificationId, s3Key);
        kafkaTemplate.send("identity.kyc", userId, event).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private KycExtractedData approvableExtraction() {
        return new KycExtractedData(DocumentType.PASSPORT, "MX", "AB123456",
                "Jane Doe", "Jane", "Doe", "01/01/1990", "01/01/2030", "MX", "F", null,
                null, null, false, false,
                0.95, 0.9, 0.9, List.of(),
                false, false, null,
                List.of(), "clear document");
    }

    private KycVerificationDecision approvedDecision() {
        return new KycVerificationDecision(KycStatus.APPROVED, 0.97, Map.of(),
                List.of(), null, true, 0,
                "Jane Doe", "1990-01-01", "AB123456", "MX",
                "all fields match", false, null);
    }

    @Test
    @DisplayName("happy path: Kafka message -> S3 download -> approved decision persisted to Mongo + Postgres")
    void kycInitiated_approvedPath_persistsToMongoAndPostgres() throws Exception {
        when(stage1.extract(any(), anyString(), anyString())).thenReturn(approvableExtraction());
        when(stage2.compare(any(), any())).thenReturn(approvedDecision());

        String userId = UUID.randomUUID().toString();
        String verificationId = UUID.randomUUID().toString();
        String s3Key = uploadTestDocument();

        publishKycInitiated(userId, verificationId, s3Key);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            var doc = kycDocumentRepository.findByVerificationIdAndUserId(verificationId, userId);
            assertThat(doc).isPresent();
            assertThat(doc.get().getStatus()).isEqualTo(KycStatus.APPROVED);
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<com.nexus.kyc.infrastructure.jpa.KycAuditEntryJPA> entries =
                    kycAuditRepository.findByVerificationIdOrderBySubmittedAtAsc(UUID.fromString(verificationId));
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getDecision()).isEqualTo("APPROVED");
        });
    }

    @Test
    @DisplayName("Stage1 quality rejection: no Stage2 call, persisted as REJECTED")
    void kycInitiated_stage1RejectsQuality_persistsRejectedWithoutStage2() throws Exception {
        when(stage1.extract(any(), anyString(), anyString()))
                .thenThrow(new com.nexus.kyc.domain.exception.DocumentQualityException(
                        "blurry", List.of(com.nexus.kyc.domain.model.enums.RejectionReason.DOCUMENT_UNREADABLE)));

        String userId = UUID.randomUUID().toString();
        String verificationId = UUID.randomUUID().toString();
        String s3Key = uploadTestDocument();

        publishKycInitiated(userId, verificationId, s3Key);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var doc = kycDocumentRepository.findByVerificationIdAndUserId(verificationId, userId);
            assertThat(doc).isPresent();
            assertThat(doc.get().getStatus()).isEqualTo(KycStatus.REJECTED);
        });

        org.mockito.Mockito.verify(stage2, org.mockito.Mockito.never()).compare(any(), any());
    }
}
