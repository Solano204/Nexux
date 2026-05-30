package com.nexus.risk.agent.tools;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * External Credit Tool — mock Buro de Credito integration.
 *
 * OPTIONAL — only call when user has < 6 months internal history.
 * Simulates querying Mexico's credit bureau for external credit data.
 * Deterministic mock: same userId always produces same score (hash-seeded RNG).
 * In production: calls actual Buro de Credito API via AWS Lambda bridge.
 * Slow tool (~500ms) — plan should mark as non-parallel with income tool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalCreditTool {

    private final ObservationRegistry observationRegistry;

    @Tool(name = "external_credit_tool",
            description = """
            Queries mock Buro de Credito (Mexican credit bureau) data.
            Only call if user has < 6 months internal transaction history.
            Returns: external credit score (300-850), payment history grade,
            derogatory marks, number of inquiries, credit utilization.
            Slow tool (~500ms). Deterministic mock for portfolio demo.
            """)
    public String getExternalCredit(
            @ToolParam(description = "User UUID") String userId) {

        Observation obs = Observation.createNotStarted(
                "risk.tool.external_credit", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            // Deterministic mock based on userId hash
            int seed = userId.hashCode();
            var rng = new Random(seed);
            int score = 400 + rng.nextInt(450);

            var result = java.util.Map.of(
                    "userId", userId,
                    "externalCreditScore", score,
                    "paymentHistoryGrade",
                    score >= 750 ? "A" : score >= 670 ? "B"
                            : score >= 580 ? "C" : "D",
                    "hasDerogatory", score < 550 && rng.nextBoolean(),
                    "numberOfInquiries", rng.nextInt(5),
                    "creditUtilization", Math.round(rng.nextDouble() * 50.0) / 100.0,
                    "oldestAccountMonths", 12 + rng.nextInt(60),
                    "dataSource", "MOCK_BURO_DE_CREDITO");

            obs.event(Observation.Event.of("tool.success"));
            return toJson(result);

        } catch (Exception e) {
            obs.error(e);
            log.error("ExternalCreditTool failed: userId={}", userId);
            return "{\"error\":\"TOOL_FAILURE\",\"message\":\"External credit unavailable\"}";
        } finally {
            obs.stop();
        }
    }

    private String toJson(Object obj) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj); }
        catch (Exception e) { return obj.toString(); }
    }
}