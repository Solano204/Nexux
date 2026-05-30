//package com.nexus.transaction.integration;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.nexus.transaction.domain.model.enums.TransactionStatus;
//import com.nexus.transaction.domain.model.enums.TransactionType;
//import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
//import com.nexus.transaction.web.dto.response.TransactionResponse;
//import org.junit.jupiter.api.*;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.http.MediaType;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.web.servlet.MockMvc;
//import org.testcontainers.containers.KafkaContainer;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.elasticsearch.ElasticsearchContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//import org.testcontainers.utility.DockerImageName;
//
//import java.math.BigDecimal;
//import java.util.UUID;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
//
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@AutoConfigureMockMvc
//@Testcontainers
//@Tag("integration")
//class TransactionFlowIntegrationTest {
//
//    @Container
//    static final PostgreSQLContainer<?> postgres =
//            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
//                    .withDatabaseName("nexus_transaction_test")
//                    .withUsername("nexus_test")
//                    .withPassword("nexus_test");
//
//    @Container
//    static final KafkaContainer kafka =
//            new KafkaContainer(DockerImageName.parse(
//                    "confluentinc/cp-kafka:7.6.0"));
//
//    @Container
//    static final ElasticsearchContainer elasticsearch =
//            new ElasticsearchContainer(DockerImageName.parse(
//                    "docker.elastic.co/elasticsearch/elasticsearch:8.13.0"))
//                    .withEnv("discovery.type", "single-node")
//                    .withEnv("xpack.security.enabled", "false");
//
//    @Autowired MockMvc mockMvc;
//    @Autowired ObjectMapper objectMapper;
//
//    @DynamicPropertySource
//    static void configureProperties(DynamicPropertyRegistry registry) {
//        registry.add("spring.datasource.url", postgres::getJdbcUrl);
//        registry.add("spring.datasource.username", postgres::getUsername);
//        registry.add("spring.datasource.password", postgres::getPassword);
//        registry.add("spring.kafka.bootstrap-servers",
//                kafka::getBootstrapServers);
//        registry.add("spring.kafka.streams.bootstrap-servers",
//                kafka::getBootstrapServers);
//        registry.add("spring.elasticsearch.uris",
//                () -> "http://" + elasticsearch.getHttpHostAddress());
//        registry.add("spring.cloud.config.enabled", () -> "false");
//        registry.add("eureka.client.enabled", () -> "false");
//    }
//
//    @Test
//    @DisplayName("POST /transfer creates INITIATED transaction with idempotency")
//    void initiateTransfer_createsTransaction() throws Exception {
//        String idempotencyKey = UUID.randomUUID().toString();
//        UUID userId = UUID.randomUUID();
//        UUID sourceAccountId = UUID.randomUUID();
//        UUID targetAccountId = UUID.randomUUID();
//
//        var request = new InitiateTransactionRequest(
//                idempotencyKey,
//                sourceAccountId, targetAccountId,
//                null, null,
//                new BigDecimal("1500.00"),
//                "MXN",
//                TransactionType.INTERNAL_TRANSFER,
//                null,
//                "Payment for services", null, null, null
//        );
//
//        String body = mockMvc.perform(post("/api/v1/transactions/transfer")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .header("X-User-Id", userId.toString())
//                        .header("X-Forwarded-For", "192.168.1.100")
//                        .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isAccepted())
//                .andExpect(jsonPath("$.transactionId").exists())
//                .andExpect(jsonPath("$.status").value("INITIATED"))
//                .andExpect(jsonPath("$.amount").value(1500.00))
//                .andReturn()
//                .getResponse()
//                .getContentAsString();
//
//        TransactionResponse response =
//                objectMapper.readValue(body, TransactionResponse.class);
//        assertThat(response.transactionId()).isNotNull();
//    }
//
//    @Test
//    @DisplayName("Idempotent: same idempotency key returns same transaction")
//    void idempotentRequest_returnsSameTransaction() throws Exception {
//        String idempotencyKey = UUID.randomUUID().toString();
//        UUID userId = UUID.randomUUID();
//
//        var request = new InitiateTransactionRequest(
//                idempotencyKey,
//                UUID.randomUUID(), UUID.randomUUID(),
//                null, null,
//                new BigDecimal("250.00"), "MXN",
//                TransactionType.INTERNAL_TRANSFER,
//                null, "Test", null, null, null
//        );
//
//        // First request
//        String firstBody = mockMvc.perform(
//                        post("/api/v1/transactions/transfer")
//                                .contentType(MediaType.APPLICATION_JSON)
//                                .header("X-User-Id", userId.toString())
//                                .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isAccepted())
//                .andReturn().getResponse().getContentAsString();
//
//        // Second request — same idempotency key
//        String secondBody = mockMvc.perform(
//                        post("/api/v1/transactions/transfer")
//                                .contentType(MediaType.APPLICATION_JSON)
//                                .header("X-User-Id", userId.toString())
//                                .content(objectMapper.writeValueAsString(request)))
//                .andExpect(status().isAccepted())
//                .andReturn().getResponse().getContentAsString();
//
//        TransactionResponse first =
//                objectMapper.readValue(firstBody, TransactionResponse.class);
//        TransactionResponse second =
//                objectMapper.readValue(secondBody, TransactionResponse.class);
//
//        // Same transaction returned — idempotency preserved
//        assertThat(first.transactionId())
//                .isEqualTo(second.transactionId());
//    }
//
//    @Test
//    @DisplayName("GET /transactions returns paginated history")
//    void getHistory_returnsPaginatedResults() throws Exception {
//        UUID userId = UUID.randomUUID();
//
//        // Create a transaction first
//        mockMvc.perform(post("/api/v1/transactions/transfer")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .header("X-User-Id", userId.toString())
//                        .content(objectMapper.writeValueAsString(
//                                new InitiateTransactionRequest(
//                                        UUID.randomUUID().toString(),
//                                        UUID.randomUUID(), UUID.randomUUID(),
//                                        null, null,
//                                        new BigDecimal("100.00"), "MXN",
//                                        TransactionType.INTERNAL_TRANSFER,
//                                        null, "History test", null, null, null))))
//                .andExpect(status().isAccepted());
//
//        // Fetch history
//        mockMvc.perform(get("/api/v1/transactions")
//                        .header("X-User-Id", userId.toString())
//                        .param("page", "0")
//                        .param("size", "20"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.content").isArray())
//                .andExpect(jsonPath("$.content[0].status")
//                        .value("INITIATED"));
//    }
//}