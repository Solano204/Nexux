package com.nexus.transaction.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.transaction.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Transaction Service Contract Tests.
 *
 * Verifies the /api/v1/transactions response contracts consumers depend on:
 *   mobile/web client        → transfer/payment initiation, history, search
 *   nexus-api-gateway        → auth boundary (X-User-Id) enforced here,
 *                               not re-validated downstream
 *
 * Changing any of these response shapes or status codes is a BREAKING
 * CHANGE and requires coordination with all consumers — same rationale as
 * AccountServiceContractTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("contract")
class TransactionContractTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    private static final String VALID_BODY = """
            {
              "idempotencyKey": "contract-test-key-0001",
              "sourceAccountId": "11111111-1111-1111-1111-111111111111",
              "targetAccountId": "22222222-2222-2222-2222-222222222222",
              "amount": 100.00,
              "currency": "MXN",
              "transactionType": "INTERNAL_TRANSFER"
            }
            """;

    @Test
    @DisplayName("CONTRACT: POST /transfer without X-User-Id returns 401")
    void initiateTransfer_missingUserId_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CONTRACT: POST /transfer with a valid request returns 202 ACCEPTED with an INITIATED transaction")
    void initiateTransfer_validRequest_returns202Accepted() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                        .contentType("application/json")
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.amount").value(100.00));
    }

    @Test
    @DisplayName("CONTRACT: POST /transfer with amount below 0.01 fails validation with 400")
    void initiateTransfer_amountBelowMinimum_returns400() throws Exception {
        String invalidBody = """
                {
                  "idempotencyKey": "contract-test-key-0002",
                  "sourceAccountId": "11111111-1111-1111-1111-111111111111",
                  "amount": 0.00,
                  "transactionType": "INTERNAL_TRANSFER"
                }
                """;
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                        .contentType("application/json")
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CONTRACT: GET /{transactionId} for a non-existent transaction returns 404")
    void getTransaction_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/{id}",
                        "99999999-9999-9999-9999-999999999999")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CONTRACT: GET history without X-User-Id returns 401")
    void getHistory_missingUserId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CONTRACT: GET history with a valid user returns a page structure")
    void getHistory_validUser_returnsPageStructure() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("CONTRACT: retrying /transfer with the same idempotencyKey returns the original transaction, not a duplicate")
    void initiateTransfer_duplicateIdempotencyKey_returnsOriginalTransaction() throws Exception {
        String body = """
                {
                  "idempotencyKey": "contract-test-key-idempotent",
                  "sourceAccountId": "33333333-3333-3333-3333-333333333333",
                  "targetAccountId": "44444444-4444-4444-4444-444444444444",
                  "amount": 50.00,
                  "currency": "MXN",
                  "transactionType": "INTERNAL_TRANSFER"
                }
                """;

        String firstResponse = mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000002")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000002")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Not a raw string comparison: the second call re-reads the
        // transaction from Postgres, whose NUMERIC/timestamptz columns
        // round-trip amount/initiatedAt at different precision than the
        // freshly-built in-memory response from the first call (e.g.
        // "50.00" vs "50.0000") even though it's the same transaction.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(firstResponse);
        JsonNode second = mapper.readTree(secondResponse);

        assertThat(second.get("transactionId").asText()).isEqualTo(first.get("transactionId").asText());
        assertThat(second.get("status").asText()).isEqualTo(first.get("status").asText());
        assertThat(second.get("amount").decimalValue()).isEqualByComparingTo(first.get("amount").decimalValue());
    }

    @Test
    @DisplayName("CONTRACT: actuator health returns UP")
    void actuatorHealth_returnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
