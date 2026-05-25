package com.nexus.fraud.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Behavioral Analysis Tool — User behavioral pattern analysis.
 *
 * Reads user behavioral profile from Redis:
 * Key: user:behavioral:{userId}
 * Written by: nexus-risk-scoring-service (periodic deep analysis)
 *
 * Computes deviations:
 * - Amount: Z-score against user's mean/stddev
 * - Time-of-day: vs user's typical activity hours
 * - Transaction type: frequency vs historical distribution
 * - Device: known vs unknown device fingerprint
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehavioralAnalysisTool {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    @Tool(
            name = "behavioral_analysis_tool",
            description = """
            Analyzes this transaction against the user's historical
            behavioral patterns. Detects: unusual amounts (Z-score),
            atypical time-of-day activity, rare transaction types,
            unknown devices, new counterparties.
            Returns deviations list and behavioralStatus.
            """
    )
    public String analyzeBehavior(
            @ToolParam(description = "User ID")
            String userId,
            @ToolParam(description = "Transaction amount as string")
            String amount,
            @ToolParam(description = "Transaction type")
            String transactionType,
            @ToolParam(description = "Device fingerprint, empty if absent")
            String deviceFingerprint,
            @ToolParam(description = "Target account ID, empty if external")
            String targetAccountId,
            @ToolParam(description = "Whether this is the first transaction " +
                    "to this counterparty")
            String isFirstTimeCounterparty) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.behavioral.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            // Get behavioral profile from Redis
            String profileJson = redisTemplate.opsForValue()
                    .get("user:behavioral:" + userId);

            if (profileJson == null) {
                obs.event(Observation.Event.of(
                        "behavioral.no_profile"));
                return buildNoHistoryResult(userId);
            }

            JsonNode profile = objectMapper.readTree(profileJson);
            List<BehavioralDeviation> deviations = new ArrayList<>();

            BigDecimal txnAmount = new BigDecimal(amount);

            // ── Amount deviation (Z-score) ─────────────────────
            double mean = profile.path("meanTransactionAmount")
                    .asDouble(0);
            double stddev = profile.path("stdDevTransactionAmount")
                    .asDouble(1);

            if (stddev > 0) {
                double zScore = (txnAmount.doubleValue() - mean) / stddev;

                if (Math.abs(zScore) > 3.0) {
                    deviations.add(new BehavioralDeviation(
                            "AMOUNT_ANOMALY",
                            String.format(
                                    "Amount %.2f is %.1f std deviations from " +
                                            "user's mean of %.2f",
                                    txnAmount.doubleValue(), zScore, mean),
                            BigDecimal.valueOf(
                                            Math.min(Math.abs(zScore) / 10.0, 1.0))
                                    .setScale(3, RoundingMode.HALF_UP)));
                }
            }

            // ── Time of day pattern ────────────────────────────
            int currentHour = Instant.now().atZone(
                    java.time.ZoneId.of("America/Mexico_City")).getHour();
            JsonNode activeHours = profile.path("activeHours");
            boolean isTypicalHour = false;
            if (activeHours.isArray()) {
                for (JsonNode h : activeHours) {
                    if (h.asInt() == currentHour) {
                        isTypicalHour = true;
                        break;
                    }
                }
            }
            if (!isTypicalHour) {
                deviations.add(new BehavioralDeviation(
                        "UNUSUAL_TIME",
                        String.format(
                                "Transaction at hour %d outside user's " +
                                        "typical activity hours",
                                currentHour),
                        new BigDecimal("0.25")));
            }

            // ── Transaction type frequency ─────────────────────
            JsonNode typeFreq = profile.path("transactionTypeFrequency");
            double freq = typeFreq.path(transactionType).asDouble(0.0);
            if (freq < 0.05) {
                deviations.add(new BehavioralDeviation(
                        "RARE_TRANSACTION_TYPE",
                        String.format(
                                "User makes %.1f%% of transactions as %s type",
                                freq * 100, transactionType),
                        new BigDecimal("0.30")));
            }

            // ── Unknown device ─────────────────────────────────
            if (deviceFingerprint != null &&
                    !deviceFingerprint.isBlank()) {
                JsonNode knownDevices = profile.path(
                        "knownDeviceFingerprints");
                boolean knownDevice = false;
                if (knownDevices.isArray()) {
                    for (JsonNode d : knownDevices) {
                        if (deviceFingerprint.equals(d.asText())) {
                            knownDevice = true;
                            break;
                        }
                    }
                }
                if (!knownDevice) {
                    deviations.add(new BehavioralDeviation(
                            "UNKNOWN_DEVICE",
                            "Transaction from device not previously " +
                                    "seen for this user",
                            new BigDecimal("0.35")));
                }
            }

            // ── New counterparty ───────────────────────────────
            if ("true".equalsIgnoreCase(isFirstTimeCounterparty)) {
                deviations.add(new BehavioralDeviation(
                        "NEW_COUNTERPARTY",
                        "First-ever transaction to this account",
                        new BigDecimal("0.20")));
            }

            double totalWeight = deviations.stream()
                    .mapToDouble(d -> d.weight().doubleValue())
                    .sum();

            String behavioralStatus = totalWeight == 0 ? "CONSISTENT"
                    : (totalWeight > 1.5 ? "HIGHLY_DEVIATING"
                    : "DEVIATING");

            var result = new BehavioralResult(
                    userId, deviations, totalWeight, behavioralStatus,
                    profile.path("accountAgeDays").asInt(0),
                    profile.path("totalTransactionCount").asInt(0)
            );

            obs.lowCardinalityKeyValue(
                    "behavioralStatus", behavioralStatus);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            obs.error(e);
            log.warn("Behavioral analysis failed for userId={}: {}",
                    userId, e.getMessage());
            return buildNoHistoryResult(userId);
        } finally {
            obs.stop();
        }
    }

    private String buildNoHistoryResult(String userId) {
        return String.format("""
            {"userId":"%s",
             "status":"INSUFFICIENT_HISTORY",
             "note":"New user or no behavioral profile available. " +
                    "Default to elevated caution.",
             "deviations":["NO_BEHAVIORAL_HISTORY"],
             "behavioralStatus":"UNKNOWN"}
            """, userId);
    }

    public record BehavioralDeviation(
            String category, String description, BigDecimal weight
    ) {}

    public record BehavioralResult(
            String userId, List<BehavioralDeviation> deviations,
            double totalDeviationWeight, String behavioralStatus,
            int accountAgeDays, int totalTransactionCount
    ) {}
}