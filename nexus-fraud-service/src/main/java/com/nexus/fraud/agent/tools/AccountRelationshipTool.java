package com.nexus.fraud.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Account Relationship Tool — Analyzes source→target account history.
 *
 * Queries Transaction Service Elasticsearch for prior transfers
 * between source and target accounts (last 90 days).
 *
 * Signals:
 * - NEW_RELATIONSHIP: first-ever transfer to this account
 * - ESTABLISHED_RELATIONSHIP: >10 prior successful transfers
 * - TARGET_ACCOUNT_FLAGGED: target in fraud:account:flagged Redis SET
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRelationshipTool {

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate transactionServiceClient;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "account_relationship_tool",
            description = """
            Gets history between source and target accounts.
            Checks: number of prior transfers, typical amounts,
            whether target account is flagged for fraud.
            Returns: relationshipType (NEW/ESTABLISHED),
            priorTransferCount, avgAmount, targetAccountFlagged.
            Use for INTERNAL_TRANSFER transactions.
            """
    )
    public String checkAccountRelationship(
            @ToolParam(description = "Source account ID")
            String sourceAccountId,
            @ToolParam(description = "Target account ID")
            String targetAccountId) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.account_relationship.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Check if target is flagged
            boolean targetFlagged = Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(
                            "fraud:account:flagged", targetAccountId));

            // Query Transaction Service for relationship history
            int priorTransferCount = 0;
            double avgAmount = 0.0;
            String relationshipType = "UNKNOWN";

            try {
                String url = "http://nexus-transaction-service:8086" +
                        "/internal/v1/accounts/relationship" +
                        "?source=" + sourceAccountId +
                        "&target=" + targetAccountId;

                com.fasterxml.jackson.databind.JsonNode response =
                        transactionServiceClient.getForObject(
                                url,
                                com.fasterxml.jackson.databind.JsonNode.class);

                if (response != null) {
                    priorTransferCount = response
                            .path("transferCount").asInt(0);
                    avgAmount = response.path("avgAmount")
                            .asDouble(0.0);
                }
            } catch (Exception e) {
                log.debug("Could not fetch relationship data: {}",
                        e.getMessage());
            }

            relationshipType = priorTransferCount == 0
                    ? "NEW_RELATIONSHIP"
                    : (priorTransferCount >= 10
                    ? "ESTABLISHED_RELATIONSHIP"
                    : "KNOWN_RELATIONSHIP");

            var result = new RelationshipResult(
                    sourceAccountId, targetAccountId,
                    priorTransferCount, avgAmount,
                    relationshipType, targetFlagged,
                    priorTransferCount == 0
            );

            obs.lowCardinalityKeyValue(
                    "relationshipType", relationshipType);

            if (targetFlagged) {
                obs.event(Observation.Event.of(
                        "relationship.target_flagged"));
            }

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            obs.error(e);
            return "{\"error\":\"RELATIONSHIP_CHECK_FAILED\"," +
                    "\"relationshipType\":\"UNKNOWN\"}";
        } finally {
            obs.stop();
        }
    }

    public record RelationshipResult(
            String sourceAccountId, String targetAccountId,
            int priorTransferCount, double avgAmount,
            String relationshipType, boolean targetAccountFlagged,
            boolean isFirstTransaction
    ) {}
}