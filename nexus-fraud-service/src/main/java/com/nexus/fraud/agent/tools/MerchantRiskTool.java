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

import java.util.ArrayList;
import java.util.List;

/**
 * Merchant Risk Tool — Checks merchant blacklist and risk profile.
 *
 * Redis data structures used:
 * - fraud:merchant:blacklist (SET) — O(1) blacklist check
 * - fraud:merchant:risk:{merchantId} (HASH) — risk profile
 * - fraud:account:flagged (SET) — target account fraud flag
 *
 * High-risk MCCs (from ISO 18245):
 * - 6051: Money Transfer/Non-Financial Institutions — VERY_HIGH
 * - 7995: Betting/Casino/Gambling — HIGH
 * - 5912: Drug Stores — MEDIUM
 * - 5812: Restaurants — LOW
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantRiskTool {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    // High-risk MCCs
    private static final java.util.Map<String, String> MCC_RISK =
            java.util.Map.of(
                    "6051", "VERY_HIGH",  // Money Transfer
                    "6050", "VERY_HIGH",  // Non-Financial Institutions
                    "7995", "HIGH",       // Gambling
                    "6011", "HIGH",       // ATM Cash Advances
                    "4829", "HIGH",       // Wire Transfer
                    "5912", "MEDIUM",     // Drug Stores
                    "5999", "MEDIUM",     // Miscellaneous Retail
                    "5812", "LOW",        // Restaurants
                    "5411", "LOW",        // Grocery Stores
                    "5541", "LOW"         // Gas Stations
            );

    @Tool(
            name = "merchant_risk_tool",
            description = """
            Checks merchant risk level and blacklist status.
            Queries Redis blacklist (O(1)), merchant risk profile,
            and ISO 18245 MCC risk classification.
            BLACKLISTED merchants cause immediate REJECT.
            Returns: riskLevel (LOW/MEDIUM/HIGH/VERY_HIGH/BLACKLISTED),
            isBlacklisted, mccRiskCategory, recentAlerts.
            """
    )
    public String checkMerchantRisk(
            @ToolParam(description = "Merchant ID (may be null for P2P)")
            String merchantId,
            @ToolParam(description = "Merchant name")
            String merchantName,
            @ToolParam(description = "ISO 18245 MCC code")
            String merchantCategoryCode,
            @ToolParam(description = "Target account ID")
            String targetAccountId) {

        Observation obs = Observation.createNotStarted(
                "fraud.tool.merchant_risk.internal",
                observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            List<String> alerts = new ArrayList<>();
            boolean isBlacklisted = false;

            // Check merchant blacklist
            if (merchantId != null && !merchantId.isBlank()) {
                isBlacklisted = Boolean.TRUE.equals(
                        redisTemplate.opsForSet().isMember(
                                "fraud:merchant:blacklist", merchantId));

                if (isBlacklisted) {
                    alerts.add("MERCHANT_BLACKLISTED: " +
                            merchantId + " is on fraud blacklist");
                }

                // Check recent fraud alerts for this merchant
                List<String> recentAlerts = redisTemplate
                        .opsForList()
                        .range("fraud:merchant:alerts:" + merchantId,
                                0, 9);
                if (recentAlerts != null) {
                    alerts.addAll(recentAlerts);
                }
            }

            // Check target account flagging
            boolean targetAccountFlagged = false;
            if (targetAccountId != null && !targetAccountId.isBlank()) {
                targetAccountFlagged = Boolean.TRUE.equals(
                        redisTemplate.opsForSet().isMember(
                                "fraud:account:flagged", targetAccountId));
                if (targetAccountFlagged) {
                    alerts.add("TARGET_ACCOUNT_FLAGGED: " +
                            targetAccountId +
                            " involved in confirmed fraud cases");
                }
            }

            // Get MCC risk level
            String mccRisk = MCC_RISK.getOrDefault(
                    merchantCategoryCode, "UNKNOWN");

            if ("VERY_HIGH".equals(mccRisk) ||
                    "HIGH".equals(mccRisk)) {
                alerts.add("HIGH_RISK_MCC_" + mccRisk + ": " +
                        merchantCategoryCode +
                        " merchant category is elevated risk");
            }

            // Determine overall risk level
            String riskLevel;
            if (isBlacklisted || targetAccountFlagged) {
                riskLevel = "BLACKLISTED";
            } else if ("VERY_HIGH".equals(mccRisk)) {
                riskLevel = "VERY_HIGH";
            } else if ("HIGH".equals(mccRisk) || !alerts.isEmpty()) {
                riskLevel = "HIGH";
            } else {
                riskLevel = mccRisk.equals("UNKNOWN") ? "MEDIUM" : mccRisk;
            }

            var result = new MerchantRiskResult(
                    merchantId, merchantName, merchantCategoryCode,
                    mccRisk, riskLevel, isBlacklisted,
                    targetAccountFlagged, alerts
            );

            obs.lowCardinalityKeyValue("riskLevel", riskLevel);

            if (isBlacklisted) {
                obs.event(Observation.Event.of("merchant.blacklisted"));
            }

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            obs.error(e);
            log.warn("Merchant risk check failed: {}", e.getMessage());
            return "{\"riskLevel\":\"UNKNOWN\"," +
                    "\"error\":\"MERCHANT_CHECK_FAILED\"}";
        } finally {
            obs.stop();
        }
    }

    public record MerchantRiskResult(
            String merchantId, String merchantName,
            String merchantCategoryCode, String mccRiskCategory,
            String riskLevel, boolean isBlacklisted,
            boolean targetAccountFlagged, List<String> alerts
    ) {}
}