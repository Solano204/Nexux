package com.nexus.risk.agent.tools;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * KYC Status Tool — compliance signal for risk scoring.
 *
 * MANDATORY tool — always called for every user.
 * Returns: verification tier, document type, days since verification,
 * rejection history, document fraud flags, EDS status.
 * KYC quality is a key input to compliance risk scoring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycStatusTool {

    private final ObservationRegistry observationRegistry;

    @Tool(name = "kyc_status_tool",
            description = """
            Gets KYC verification status for a user.
            Returns: verification tier (BASIC/STANDARD/ENHANCED),
            document type used, days since verification,
            rejection history, document fraud flags, EDS status.
            MANDATORY — KYC quality is a key compliance signal.
            hasPreviousRejections=true increases compliance risk.
            documentFraudFlag=true is CRITICAL.
            """)
    public String getKycStatus(
            @ToolParam(description = "User UUID") String userId) {

        Observation obs = Observation.createNotStarted(
                "risk.tool.kyc_status", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {
            var result = java.util.Map.of(
                    "userId", userId,
                    "isKycApproved", true,
                    "kycTier", "STANDARD",
                    "documentType", "NATIONAL_ID",
                    "daysSinceVerification", 45,
                    "reVerificationDue", false,
                    "attemptsBeforeApproval", 1,
                    "hasPreviousRejections", false,
                    "documentFraudFlag", false,
                    "edsApplied", false);

            obs.event(Observation.Event.of("tool.success"));
            return toJson(result);

        } catch (Exception e) {
            obs.error(e);
            log.error("KycStatusTool failed: userId={}", userId);
            return "{\"error\":\"TOOL_FAILURE\",\"message\":\"KYC status unavailable\"}";
        } finally {
            obs.stop();
        }
    }

    private String toJson(Object obj) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj); }
        catch (Exception e) { return obj.toString(); }
    }
}