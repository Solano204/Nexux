package com.nexus.assistant.agent.tools;

import com.nexus.assistant.infrastructure.client.FraudServiceClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Fraud Alerts Tool — Retrieves recent security events.
 *
 * Security design: ONLY returns user-facing summaries.
 * Does NOT expose: raw risk scores, triggering factors,
 * model internals. These would help attackers bypass fraud detection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudAlertsTool {

    private final FraudServiceClient fraudServiceClient;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "get_fraud_alerts",
            description = """
            Retrieves recent security alerts and blocked transactions.
            Use when user asks about blocked transactions, security
            alerts, or suspicious activity.
            Returns: date, blocked amount, and user-facing explanation.
            Does NOT expose technical fraud analysis details.
            """
    )
    public String getFraudAlerts(
            @ToolParam(description = "Days to look back, default 30")
            int daysBack) {

        Observation obs = Observation.createNotStarted(
                "ai.tool.get_fraud_alerts", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            String userId = SecurityContextHolder.getContext()
                    .getAuthentication().getName();

            return fraudServiceClient.getRecentAlertSummaries(
                    userId, daysBack > 0 ? daysBack : 30);

        } catch (Exception e) {
            obs.error(e);
            log.warn("Fraud alerts tool failed: {}", e.getMessage());
            return "{\"error\": \"ALERTS_UNAVAILABLE\", " +
                    "\"message\": \"Security alerts temporarily unavailable.\"}";
        } finally {
            obs.stop();
        }
    }
}