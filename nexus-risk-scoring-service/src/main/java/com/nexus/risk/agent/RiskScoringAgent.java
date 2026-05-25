package com.nexus.risk.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.risk.agent.model.*;
import com.nexus.risk.domain.model.*;
import com.nexus.risk.domain.model.enums.RiskTier;
import com.nexus.risk.domain.exception.RiskScoringException;
import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import com.nexus.risk.infrastructure.redis.RiskProfileCacheService;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Risk Scoring Agent — Section 11 Plan-then-Act implementation.
 *
 * TWO phases:
 *
 * PLAN phase (riskPlanningClient):
 * - Receives UserContext
 * - Generates RiskScoringPlan: which tools, in what order, parallel groups
 * - Different plans for new vs long-term users
 * - entity(RiskScoringPlan.class) — Section 3 structured output
 *
 * EXECUTE phase (riskExecutionClient):
 * - Starts ReAct loop with the generated plan
 * - internalToolExecutionEnabled=false: we control the loop
 * - Named Observation per step → Zipkin child span per tool
 * - Parallel tool execution via StructuredTaskScope (Section 11)
 * - MAX_RISK_AGENT_STEPS = 12 (generous: 8 tools + overhead)
 *
 * SYNTHESIZE phase:
 * - Final prompt with all tool results assembled
 * - entity(RiskProfile.class) → full structured profile
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

    private final Timer computationTimer;
    private final Counter successCounter;
    private final Counter failureCounter;

    public RiskScoringAgent(
            @Qualifier("riskPlanningClient")
            ChatClient planningClient,
            @Qualifier("riskExecutionClient")
            ChatClient executionClient,
            ToolCallingManager toolCallingManager,
            RiskProfileRepository profileRepository,
            RiskProfileCacheService cacheService,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            MeterRegistry meterRegistry) {

        this.planningClient = planningClient;
        this.executionClient = executionClient;
        this.toolCallingManager = toolCallingManager;
        this.profileRepository = profileRepository;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;

        this.computationTimer =
                Timer.builder("risk.profile.computation.duration.seconds")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);

        this.successCounter =
                Counter.builder("risk.profile.computation.total")
                        .tag("outcome", "SUCCESS").register(meterRegistry);

        this.failureCounter =
                Counter.builder("risk.profile.computation.total")
                        .tag("outcome", "FAILED").register(meterRegistry);
    }

    /**
     * Main entry point — computes a complete risk profile for a user.
     *
     * @param userId    The user to score
     * @param triggeredBy  SCHEDULED, EVENT_TRIGGERED, or MANUAL
     * @return          Complete RiskProfile persisted to PostgreSQL
     */
    public RiskProfile computeRiskProfile(String userId,
                                          String triggeredBy) {

        Observation obs = Observation.createNotStarted(
                        "risk.agent.compute", observationRegistry)
                .lowCardinalityKeyValue("trigger", triggeredBy)
                .start();

        Timer.Sample sample = Timer.start();

        Instant startedAt = Instant.now();

        try (Observation.Scope scope = obs.openScope()) {

            // ── Phase 1: Load context for planning ────────────────
            UserContext context = loadUserContext(userId);

            // ── Phase 2: Generate plan ─────────────────────────────
            RiskScoringPlan plan = generatePlan(userId, context);

            log.info("Risk plan: userId={} depth={} steps={} " +
                            "estimatedSeconds={}",
                    userId,
                    plan.expectedAnalysisDepth(),
                    plan.steps().size(),
                    plan.estimatedDurationSeconds());

            obs.lowCardinalityKeyValue("analysisDepth",
                    plan.expectedAnalysisDepth());

            // ── Phase 3: Execute plan ──────────────────────────────
            int nextVersion = getNextVersion(userId);
            RiskProfile profile = executePlan(
                    plan, userId, context, nextVersion, startedAt,
                    triggeredBy);

            // ── Phase 4: Persist + cache ───────────────────────────
            persistProfile(profile, startedAt,
                    System.currentTimeMillis() - startedAt.toEpochMilli());
            cacheService.cacheProfile(profile);

            successCounter.increment();
            obs.event(Observation.Event.of("risk.computation.success"));

            log.info("Risk profile computed: userId={} " +
                            "score={} tier={} confidence={}",
                    userId, profile.overallRiskScore(),
                    profile.riskTier(), profile.confidenceLevel());

            return profile;

        } catch (Exception e) {
            obs.error(e);
            failureCounter.increment();
            log.error("Risk scoring failed: userId={} {}",
                    userId, e.getMessage(), e);
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
        // Load from Redis/database — quick metadata only
        // Tools fetch the detailed data during execution
        var existing = profileRepository.findLatestByUserId(userId);

        return UserContext.builder()
                .userId(userId)
                .accountAgeMonths(getAccountAgeMonths(userId))
                .accountTypes(getAccountTypes(userId))
                .hasTransactionHistory(hasTransactionHistory(userId))
                .monthsOfHistoryAvailable(getMonthsOfHistory(userId))
                .previousRiskTier(existing.map(p -> p.getRiskTier())
                        .orElse(null))
                .kycStatus(getKycStatus(userId))
                .recentFraudFlags(hasRecentFraudFlags(userId))
                .significantBehavioralChange(
                        hasSignificantBehavioralChange(userId))
                .build();
    }

    // ══════════════════════════════════════════════════════════
    // PHASE 2 — Generate Plan
    // ══════════════════════════════════════════════════════════

    private RiskScoringPlan generatePlan(String userId,
                                         UserContext context) {

        Observation obs = Observation.createNotStarted(
                "risk.agent.plan", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

            String planningPrompt = """
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
                - transaction_history_tool [MANDATORY]
                - account_age_tool [MANDATORY]
                - kyc_status_tool [MANDATORY]
                - external_credit_tool [OPTIONAL - only if history < 6 months]
                - spending_pattern_tool [RECOMMENDED if history >= 3 months]
                - income_analysis_tool [RECOMMENDED if history >= 6 months]
                - counterparty_analysis_tool [if history >= 3 months]
                - geographic_risk_tool [if fraud flags OR HIGH tier]
                
                PLANNING RULES:
                1. Mark independent tools as canParallel=true
                   (transaction + account_age + kyc can all run in parallel)
                2. If accountAgeMonths < 1: SHALLOW — 3 tools only
                3. If accountAgeMonths >= 12: COMPREHENSIVE — all tools
                4. If recentFraudFlags=true: add geographic_risk_tool
                5. If previousRiskTier=HIGH or VERY_HIGH: use all tools
                6. external_credit_tool is slow — only for new users
                
                Return ONLY valid JSON matching RiskScoringPlan schema.
                """.formatted(
                    context.accountAgeMonths(),
                    context.accountTypes(),
                    context.hasTransactionHistory(),
                    context.monthsOfHistoryAvailable(),
                    context.previousRiskTier() != null
                            ? context.previousRiskTier() : "NONE",
                    context.kycStatus(),
                    context.recentFraudFlags(),
                    context.significantBehavioralChange());

            return planningClient.prompt()
                    .user(planningPrompt)
                    .call()
                    .entity(RiskScoringPlan.class);

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // PHASE 3 — Execute Plan (Section 11 ReAct loop)
    // ══════════════════════════════════════════════════════════

    private RiskProfile executePlan(RiskScoringPlan plan,
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
                "Compute a comprehensive risk profile for user: " + userId +
                        "\nFollow this plan: " +
                        serializePlan(plan) +
                        "\nUser context: " + serializeContext(context)));

        var prompt = new org.springframework.ai.chat.prompt
                .Prompt(messages, toolOptions);

        ChatResponse response = executionClient.prompt()
                .messages(messages)
                .options(toolOptions)
                .call()
                .chatResponse();

        int steps = 0;

        while (response.hasToolCalls() &&
                steps < MAX_RISK_AGENT_STEPS) {

            // Named Observation per step → Zipkin child span
            Observation stepObs = Observation.createNotStarted(
                    "risk.agent.step." + steps,
                    observationRegistry).start();

            try (Observation.Scope stepScope = stepObs.openScope()) {

                // Check if multiple calls can run in parallel
                List<org.springframework.ai.chat.model.ToolCall>
                        pendingCalls = response.getResult()
                        .getOutput().getToolCalls();

                if (pendingCalls.size() > 1 &&
                        areParallelInPlan(pendingCalls, plan)) {

                    // Parallel tool execution via Structured Concurrency
                    executeParallelToolCalls(
                            pendingCalls, messages, prompt, toolOptions);

                    prompt = new org.springframework.ai.chat.prompt
                            .Prompt(messages, toolOptions);

                } else {
                    // Sequential execution
                    ToolExecutionResult result =
                            toolCallingManager.executeToolCalls(
                                    prompt, response);

                    prompt = new org.springframework.ai.chat.prompt
                            .Prompt(result.conversationHistory(),
                            toolOptions);
                }

                response = executionClient.prompt()
                        .messages(prompt.getInstructions())
                        .options(toolOptions)
                        .call()
                        .chatResponse();

                stepObs.event(Observation.Event.of(
                        "tool.step.complete"));
                steps++;

            } finally {
                stepObs.stop();
            }
        }

        // Synthesis: produce structured RiskProfile
        return synthesizeProfile(userId, version, startedAt,
                triggeredBy, plan, prompt);
    }

    /**
     * Parallel tool execution — Structured Concurrency (Section 11).
     * When the plan identifies parallel-safe tools, run them simultaneously.
     */
    private void executeParallelToolCalls(
            List<org.springframework.ai.chat.model.ToolCall> calls,
            List<Message> messages,
            org.springframework.ai.chat.prompt.Prompt currentPrompt,
            ToolCallingChatOptions options) {

        try (var scope =
                     new StructuredTaskScope.ShutdownOnFailure()) {

            List<StructuredTaskScope.Subtask<ToolExecutionResult>>
                    futures = calls.stream()
                    .map(call -> scope.fork(() ->
                            toolCallingManager.executeToolCalls(
                                    currentPrompt, null)))
                    .toList();

            scope.join().throwIfFailed();

            // Add all results to conversation history
            futures.forEach(f -> {
                var result = f.get();
                messages.addAll(result.conversationHistory()
                        .stream()
                        .filter(m -> m instanceof ToolResponseMessage)
                        .toList());
            });

        } catch (Exception e) {
            log.warn("Parallel tool execution failed, " +
                    "falling back to sequential: {}", e.getMessage());
            // Parallel failure is non-fatal — tools will be called
            // sequentially in the next iteration of the main loop
        }
    }

    // ══════════════════════════════════════════════════════════
    // SYNTHESIS
    // ══════════════════════════════════════════════════════════

    private RiskProfile synthesizeProfile(String userId,
                                          int version,
                                          Instant startedAt,
                                          String triggeredBy,
                                          RiskScoringPlan plan,
                                          org.springframework.ai.chat.prompt.Prompt finalPrompt) {

        Observation obs = Observation.createNotStarted(
                "risk.agent.synthesis", observationRegistry).start();

        try (Observation.Scope scope = obs.openScope()) {

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

            List<Message> synthMessages =
                    new ArrayList<>(finalPrompt.getInstructions());
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

    private void persistProfile(RiskProfile profile,
                                Instant startedAt,
                                long durationMs) {
        var entity = RiskProfileJpaEntity.from(profile,
                startedAt, durationMs);
        profileRepository.save(entity);
    }

    private int getNextVersion(String userId) {
        return profileRepository.findLatestVersionByUserId(userId)
                .map(v -> v + 1)
                .orElse(1);
    }

    private boolean areParallelInPlan(
            List<org.springframework.ai.chat.model.ToolCall> calls,
            RiskScoringPlan plan) {
        Set<String> callNames = new HashSet<>();
        calls.forEach(c -> callNames.add(c.name()));

        return plan.steps().stream()
                .filter(s -> callNames.contains(s.toolName()))
                .allMatch(RiskScoringPlan.RiskScoringStep::canParallel);
    }

    private String serializePlan(RiskScoringPlan plan) {
        try { return objectMapper.writeValueAsString(plan); }
        catch (Exception e) { return plan.toString(); }
    }

    private String serializeContext(UserContext context) {
        try { return objectMapper.writeValueAsString(context); }
        catch (Exception e) { return context.toString(); }
    }

    // Context loaders — stub implementations
    private int getAccountAgeMonths(String userId) { return 12; }
    private List<String> getAccountTypes(String userId) {
        return List.of("CHECKING", "SAVINGS"); }
    private boolean hasTransactionHistory(String userId) { return true; }
    private int getMonthsOfHistory(String userId) { return 12; }
    private String getKycStatus(String userId) { return "VERIFIED"; }
    private boolean hasRecentFraudFlags(String userId) { return false; }
    private boolean hasSignificantBehavioralChange(String userId) { return false; }
}