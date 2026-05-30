package com.nexus.risk.config;

import com.nexus.risk.agent.tools.*;
import com.nexus.risk.config.advisor.ContentSanitizerAdvisor;   // ✅ your custom class
import com.nexus.risk.config.advisor.ErrorWrappingAdvisor;      // ✅ your custom class
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Risk Scoring Agent.
 *
 * TWO ChatClient beans:
 *
 * 1. riskPlanningClient
 *    - gpt-4o, temp=0.0, JSON, NO tools
 *    - Generates RiskScoringPlan from UserContext
 *    - Structured output: entity(RiskScoringPlan.class)
 *
 * 2. riskExecutionClient
 *    - gpt-4o, temp=0.0, JSON, ALL 8 tools registered
 *    - internalToolExecutionEnabled=false: RiskScoringAgent drives the loop
 *    - Advisor chain: ContentSanitizerAdvisor → ErrorWrappingAdvisor → SimpleLoggerAdvisor
 *
 * Temperature 0.0 on BOTH: risk scores must be deterministic.
 */
@Configuration
public class SpringAiConfig {

    private static final String RISK_SCORING_SYSTEM = """
            You are a financial risk analyst AI for Nexus Bank.
            You compute comprehensive risk profiles for users.

            Your analysis must cover:
            1. Credit risk: income stability, spending patterns, savings behavior
            2. Behavioral risk: volatility, consistency, dormancy
            3. Compliance risk: AML signals, structuring, geographic risk
            4. Velocity profile: for real-time fraud detection
            5. Behavioral profile: for AI assistant personalization

            SCORING STANDARDS:
            Overall risk score (0-100):
              0-20  = VERY_LOW:  exemplary financial behavior
             21-40  = LOW:       good profile, minor concerns
             41-60  = MEDIUM:    average, some risk factors
             61-80  = HIGH:      elevated risk, multiple signals
             81-100 = VERY_HIGH: serious concerns

            Credit score (300-850):
            Compute from: income stability, spending consistency,
            savings rate, payment regularity, account age.

            AML risk (0-100):
            Flag: structuring (sub-threshold clustering),
            high counterparty diversity with rapid movement,
            high-risk country exposure.

            Confidence level:
              0.95 = 12+ months, all tools successful
              0.80 = 6-12 months, all mandatory tools
              0.65 = 3-6 months
              0.50 = < 3 months or data gaps
              0.30 = < 1 month or critical failures

            Return ONLY valid JSON matching RiskProfile schema.
            Be deterministic: same data → same score.
            """;

    // ── 1. Planning client — generates RiskScoringPlan ────────

    @Bean("riskPlanningClient")
    public ChatClient riskPlanningClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .temperature(0.0)
                        .maxTokens(1500)
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    // ── 2. Execution client — Plan-then-Act tool loop ─────────

    @Bean("riskExecutionClient")
    public ChatClient riskExecutionClient(
            OpenAiChatModel model,
            TransactionHistoryTool txTool,
            AccountAgeTool ageTool,
            KycStatusTool kycTool,
            ExternalCreditTool creditTool,
            SpendingPatternTool spendingTool,
            IncomeAnalysisTool incomeTool,
            CounterpartyAnalysisTool counterpartyTool,
            GeographicRiskTool geoTool) {

        return ChatClient.builder(model)
                .defaultSystem(RISK_SCORING_SYSTEM)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .temperature(0.0)
                        .maxTokens(4096)
                        .build())
                // Register all 8 tools
                .defaultTools(txTool, ageTool, kycTool, creditTool,
                        spendingTool, incomeTool, counterpartyTool, geoTool)
                .defaultAdvisors(
                        new ContentSanitizerAdvisor(),
                        new ErrorWrappingAdvisor(),
                        new SimpleLoggerAdvisor())
                .build();
    }
}