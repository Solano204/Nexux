package com.nexus.risk.agent.model;

import   com.nexus.risk.agent.model.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.domain.exception.RiskScoringException;
import com.nexus.risk.domain.model.RiskProfile;
import com.nexus.risk.infrastructure.jpa.RiskProfileJpaEntity;
import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import com.nexus.risk.infrastructure.redis.RiskProfileCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Risk Scoring Agent — Plan-then-Act implementation.
 *
 * THREE phases:
 *
 *   PLAN      — riskPlanningClient generates a RiskScoringPlan (which tools,
 *               in what order, which can run in parallel).
 *
 *   EXECUTE   — riskExecutionClient runs a ReAct loop driven by that plan.
 *               internalToolExecutionEnabled=false so we control the loop.
 *               Parallel-safe tools are executed concurrently via
 *               CompletableFuture + a cached thread-pool.
 *
 *   SYNTHESIZE — final prompt collects all tool results and produces a
 *               fully-structured RiskProfile via entity(RiskProfile.class).
 *
 * This class is a plain Spring @Component — it is NOT a Spring AI built-in.
 * It lives in com.nexus.risk.agent and is injected wherever needed
 * (e.g. NightlyRiskScoringJob) via a normal @Autowired / constructor injection.
 */
@Slf4j
@Component
public class RiskScoringAgent {

    private static final int MAX_RISK_AGENT_STEPS = 12;

    private final ChatClient planningClient;
    private final ChatClient executionClient;
    private final ToolCallingManager toolCallingManager;
    private final RiskProfileRepository profileRepository;
    private final RiskProfileCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    private final Timer   computationTimer;
    private final Counter successCounter;
    private final Counter failureCounter;

    // ──────────────────────────────────────────────────────────
    // Constructor
    // ──────────────────────────────────────────────────────────

    public RiskScoringAgent(
            @Qualifier("riskPlanningClient")  ChatClient planningClient,
            @Qualifier("riskExecutionClient") ChatClient executionClient,
            ToolCallingManager toolCallingManager,
            RiskProfileRepository profileRepository,
            RiskProfileCacheService cacheService,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            MeterRegistry meterRegistry) {

        this.planningClient      = planningClient;
        this.executionClient     = executionClient;
        this.toolCallingManager  = toolCallingManager;
        this.profileRepository   = profileRepository;
        this.cacheService        = cacheService;
        this.objectMapper        = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer              = tracer;

        this.computationTimer =
                Timer.builder("risk.profile.computation.duration.seconds")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);

        this.successCounter =
                Counter.builder("risk.profile.computation.total")
                        .tag("outcome", "SUCCESS")
                        .register(meterRegistry);

        this.failureCounter =
                Counter.builder("risk.profile.computation.total")
                        .tag("outcome", "FAILED")
                        .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════════
    // PUBLIC API  — called by NightlyRiskScoringJob and others
    // ══════════════════════════════════════════════════════════

    /**
     * Computes a complete risk profile for one user.
     *
     * @param userId      the user to score
     * @param triggeredBy SCHEDULED | EVENT_TRIGGERED | MANUAL
     * @return            persisted RiskProfile
     */
    public RiskProfile computeRiskProfile(String userId, String triggeredBy) {

        Observation obs = Observation.createNotStarted(
                        "risk.agent.compute", observationRegistry)
                .lowCardinalityKeyValue("trigger", triggeredBy)
                .start();

        Timer.Sample sample    = Timer.start();
        Instant      startedAt = Instant.now();

        try (Observation.Scope ignored = obs.openScope()) {

            // Phase 1 — lightweight context (heavy data fetched by tools)
            UserContext context = loadUserContext(userId);

            // Phase 2 — ask the planning client which tools to run
            RiskScoringPlan plan = generatePlan(userId, context);

            log.info("Risk plan: userId={} depth={} steps={} estimatedSeconds={}",
                    userId,
                    plan.expectedAnalysisDepth(),
                    plan.steps().size(),
                    plan.estimatedDurationSeconds());

            obs.lowCardinalityKeyValue("analysisDepth", plan.expectedAnalysisDepth());

            // Phase 3 — execute the plan (ReAct loop)
            int nextVersion = getNextVersion(userId);
            RiskProfile profile = executePlan(
                    plan, userId, context, nextVersion, startedAt, triggeredBy);

            // Phase 4 — persist + cache
            persistProfile(profile, startedAt,
                    System.currentTimeMillis() - startedAt.toEpochMilli());
            cacheService.cacheProfile(profile);

            successCounter.increment();
            obs.event(Observation.Event.of("risk.computation.success"));

            log.info("Risk profile computed: userId={} score={} tier={} confidence={}",
                    userId, profile.overallRiskScore(),
                    profile.riskTier(), profile.confidenceLevel());

            return profile;

        } catch (Exception e) {
            obs.error(e);
            failureCounter.increment();
            log.error("Risk scoring failed: userId={} {}", userId, e.getMessage(), e);
            throw new RiskScoringException(
                    "Risk scoring failed for user: " + userId, e);
        } finally {
            sample.stop(computationTimer);
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // PHASE 1 — Load User Context
    // ══════════════════════════════════════════════════════════

    private UserContext loadUserContext(String userId) {

        var existing = profileRepository.findLatestByUserId(userId);

        return UserContext.builder()
                .userId(userId)
                .accountAgeMonths(getAccountAgeMonths(userId))
                .accountTypes(getAccountTypes(userId))
                .hasTransactionHistory(hasTransactionHistory(userId))
                .monthsOfHistoryAvailable(getMonthsOfHistory(userId))
                .previousRiskTier(existing
                        .map(RiskProfileJpaEntity::getRiskTier)
                        .orElse(null))
                .kycStatus(getKycStatus(userId))
                .recentFraudFlags(hasRecentFraudFlags(userId))
                .significantBehavioralChange(hasSignificantBehavioralChange(userId))
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // PHASE 2 — Generate Plan
    // ══════════════════════════════════════════════════════════

    private RiskScoringPlan generatePlan(String userId, UserContext context) {

        Observation obs = Observation.createNotStarted(
                "risk.agent.plan", observationRegistry).start();

        try (Observation.Scope ignored = obs.openScope()) {

            String prompt = """
                    Generate a risk scoring plan for this user.
                    
                    User Context:
                    - Account age: %d months
                    - Account types: %s
                    - Has transaction history: %s
                    - Months of history available: %d
                    - Previous risk tier: %s
                    - KYC status: %s
                    - Recent fraud flags: %s
                    - Significant behavioral change: %s
                    
                    Available tools:
                    - transaction_history_tool   [MANDATORY]
                    - account_age_tool           [MANDATORY]
                    - kyc_status_tool            [MANDATORY]
                    - external_credit_tool       [OPTIONAL — only if history < 6 months]
                    - spending_pattern_tool      [RECOMMENDED if history >= 3 months]
                    - income_analysis_tool       [RECOMMENDED if history >= 6 months]
                    - counterparty_analysis_tool [if history >= 3 months]
                    - geographic_risk_tool       [if fraud flags OR HIGH tier]
                    
                    PLANNING RULES:
                    1. Mark independent tools canParallel=true
                       (transaction_history + account_age + kyc can all run together)
                    2. accountAgeMonths < 1   → SHALLOW        (3 tools only)
                    3. accountAgeMonths >= 12 → COMPREHENSIVE  (all applicable tools)
                    4. recentFraudFlags=true  → add geographic_risk_tool
                    5. previousRiskTier=HIGH or VERY_HIGH → use all tools
                    6. external_credit_tool is slow — only for new users
                    
                    Return ONLY valid JSON matching RiskScoringPlan schema.
                    """.formatted(
                    context.accountAgeMonths(),
                    context.accountTypes(),
                    context.hasTransactionHistory(),
                    context.monthsOfHistoryAvailable(),
                    context.previousRiskTier() != null ? context.previousRiskTier() : "NONE",
                    context.kycStatus(),
                    context.recentFraudFlags(),
                    context.significantBehavioralChange());

            return planningClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(RiskScoringPlan.class);

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // PHASE 3 — Execute Plan  (ReAct loop)
    // ══════════════════════════════════════════════════════════

    private RiskProfile executePlan(
            RiskScoringPlan plan,
            String userId,
            UserContext context,
            int version,
            Instant startedAt,
            String triggeredBy) {

        ToolCallingChatOptions toolOptions =
                ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build();

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(
                "Compute a comprehensive risk profile for user: " + userId
                        + "\nFollow this plan: "  + serialize(plan)
                        + "\nUser context: "      + serialize(context)));

        org.springframework.ai.chat.prompt.Prompt prompt =
                new org.springframework.ai.chat.prompt.Prompt(messages, toolOptions);

        ChatResponse response = executionClient.prompt()
                .messages(messages)
                .options(toolOptions)
                .call()
                .chatResponse();

        int steps = 0;

        while (hasToolCalls(response) && steps < MAX_RISK_AGENT_STEPS) {

            Observation stepObs = Observation.createNotStarted(
                    "risk.agent.step." + steps, observationRegistry).start();

            try (Observation.Scope ignored = stepObs.openScope()) {

                // AssistantMessage.ToolCall is the correct type in Spring AI 1.x
                // It is a nested record inside AssistantMessage (chat.messages package).
                // Accessor: call.name()  call.id()  call.arguments()
                ToolExecutionResult result =
                        toolCallingManager.executeToolCalls(prompt, response);
                prompt = new org.springframework.ai.chat.prompt.Prompt(
                        result.conversationHistory(), toolOptions);
                messages.clear();
                messages.addAll(result.conversationHistory());

                response = executionClient.prompt()
                        .messages(prompt.getInstructions())
                        .options(toolOptions)
                        .call()
                        .chatResponse();

                stepObs.event(Observation.Event.of("tool.step.complete"));
                steps++;

            } finally {
                stepObs.stop();
            }
        }

        return synthesizeProfile(userId, version, startedAt, triggeredBy, plan, prompt);
    }

    // ══════════════════════════════════════════════════════════
    // SYNTHESIS
    // ══════════════════════════════════════════════════════════

    private RiskProfile synthesizeProfile(
            String userId,
            int version,
            Instant startedAt,
            String triggeredBy,
            RiskScoringPlan plan,
            org.springframework.ai.chat.prompt.Prompt finalPrompt) {

        Observation obs = Observation.createNotStarted(
                "risk.agent.synthesis", observationRegistry).start();

        try (Observation.Scope ignored = obs.openScope()) {

            String synthesisPrompt = """
                    All tool data has been collected.
                    Now synthesize a complete RiskProfile.
                    
                    Guidelines:
                    - Weight income stability heavily for credit risk
                    - Weight behavioral consistency for behavioral risk
                    - Check all AML indicators for compliance risk
                    - Build velocity profile from transaction timing/amount data
                    - Build behavioral profile for fraud service cache
                    
                    Generate profile for userId=%s, version=%d.
                    Triggered by: %s
                    Analysis depth: %s
                    
                    Return ONLY valid JSON matching RiskProfile schema.
                    Be deterministic. Same input → same output.
                    """.formatted(userId, version, triggeredBy,
                    plan.expectedAnalysisDepth());

            List<Message> synthMessages = new ArrayList<>(finalPrompt.getInstructions());
            synthMessages.add(new UserMessage(synthesisPrompt));

            return executionClient.prompt()
                    .messages(synthMessages)
                    .call()
                    .entity(RiskProfile.class);

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════

    private void persistProfile(RiskProfile profile, Instant startedAt, long durationMs) {
        var entity = RiskProfileJpaEntity.from(profile, startedAt, durationMs);
        profileRepository.save(entity);
    }

    private int getNextVersion(String userId) {
        return profileRepository.findLatestVersionByUserId(userId)
                .map(v -> v + 1)
                .orElse(1);
    }

    /** Null-safe tool-call check on AssistantMessage. */
    private boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResult() == null) return false;
        var output = response.getResult().getOutput();
        return output instanceof AssistantMessage am && am.hasToolCalls();
    }

    private String serialize(Object obj) {
        try   { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return obj.toString(); }
    }

    // ── Context loader stubs (replace with real repository calls) ──
    private int          getAccountAgeMonths(String u)            { return 12; }
    private java.util.List<String> getAccountTypes(String u)      { return java.util.List.of("CHECKING", "SAVINGS"); }
    private boolean      hasTransactionHistory(String u)          { return true; }
    private int          getMonthsOfHistory(String u)             { return 12; }
    private String       getKycStatus(String u)                   { return "VERIFIED"; }
    private boolean      hasRecentFraudFlags(String u)            { return false; }
    private boolean      hasSignificantBehavioralChange(String u)  { return false; }
}