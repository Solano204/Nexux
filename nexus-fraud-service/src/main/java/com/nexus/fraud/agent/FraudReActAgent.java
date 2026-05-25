package com.nexus.fraud.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fraud.domain.model.*;
import com.nexus.fraud.domain.model.enums.*;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * FraudReActAgent — Plan-then-Act ReAct pattern (Section 11).
 *
 * Three-phase execution:
 * Phase 1 (PLAN):     LLM decides which tools to call
 * Phase 2 (EXECUTE):  Tool loop with Structured Concurrency for parallel calls
 * Phase 3 (SYNTHESIZE): LLM produces FraudDecision from all evidence
 *
 * Each phase is a named Micrometer Observation → Zipkin span.
 * Each tool call gets its own span: fraud.tool.{toolName}
 *
 * Max tool call steps: 8 (prevents runaway loops)
 * Outer timeout: 30 seconds (SAGA orchestrator times out at 60s)
 *
 * Java 25 (JEP 505, 5th preview): Structured Concurrency API uses
 * StructuredTaskScope.open(Joiner) static factory method.
 * ShutdownOnFailure/ShutdownOnSuccess subclasses are REMOVED.
 * Use Joiner.allSuccessfulOrThrow() for fail-fast semantics.
 *
 * internalToolExecutionEnabled=false means WE drive the loop,
 * giving precise control over:
 * - observability per tool call
 * - parallel execution via Structured Concurrency
 * - error handling per tool (not blanket exception)
 */
@Slf4j
@Component
public class FraudReActAgent {

    private final ChatClient planningClient;
    private final ChatClient agentClient;
    private final ChatClient synthesisClient;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    // Metrics
    private final Timer analysisTimer;
    private final Counter approveCounter;
    private final Counter reviewCounter;
    private final Counter rejectCounter;
    private final Counter hardRejectCounter;

    private static final int MAX_TOOL_CALL_STEPS = 8;

    public FraudReActAgent(
            @Qualifier("fraudPlanningClient") ChatClient planningClient,
            @Qualifier("fraudAgentClient") ChatClient agentClient,
            @Qualifier("fraudSynthesisClient") ChatClient synthesisClient,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.planningClient = planningClient;
        this.agentClient = agentClient;
        this.synthesisClient = synthesisClient;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.analysisTimer = Timer.builder("fraud.analysis.duration.seconds")
                .description("Total fraud analysis duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.approveCounter = Counter.builder("fraud.decisions.total")
                .tag("outcome", "APPROVE").register(meterRegistry);
        this.reviewCounter = Counter.builder("fraud.decisions.total")
                .tag("outcome", "REVIEW").register(meterRegistry);
        this.rejectCounter = Counter.builder("fraud.decisions.total")
                .tag("outcome", "REJECT").register(meterRegistry);
        this.hardRejectCounter = Counter.builder("fraud.hard_reject.total")
                .register(meterRegistry);
    }

    /**
     * Main entry point — analyzes a transaction and returns FraudDecision.
     *
     * @param request Full transaction context from CheckFraudCommand
     * @return Structured FraudDecision (never null — uses safe fallback)
     */
    public FraudDecision analyze(FraudAnalysisRequest request) {

        Instant startTime = Instant.now();
        Timer.Sample timerSample = Timer.start();

        Observation rootObservation = Observation.createNotStarted(
                        "fraud.analysis", observationRegistry)
                .lowCardinalityKeyValue(
                        "transactionType", request.transactionType())
                .start();

        try (Observation.Scope scope = rootObservation.openScope()) {

            log.info("Fraud analysis started: txnId={} userId={} " +
                            "amount={} type={}",
                    request.transactionId(), request.userId(),
                    request.amount(), request.transactionType());

            // ── PHASE 1: PLAN ──────────────────────────────────
            FraudAnalysisPlan plan = executePlanPhase(request);

            rootObservation.event(Observation.Event.of("plan.complete"));
            log.info("Fraud plan created: txnId={} steps={} concerns={}",
                    request.transactionId(),
                    plan.steps().size(),
                    plan.primaryConcerns());

            // ── PHASE 2: EXECUTE TOOLS ──────────────────────────
            List<Message> conversationHistory =
                    executeToolPhase(request, plan);

            rootObservation.event(Observation.Event.of("tools.complete"));

            // ── PHASE 3: SYNTHESIZE DECISION ────────────────────
            FraudDecision decision = executeSynthesisPhase(
                    request, conversationHistory, startTime);

            // Record metrics
            switch (decision.decision()) {
                case APPROVE -> approveCounter.increment();
                case REVIEW -> reviewCounter.increment();
                case REJECT -> rejectCounter.increment();
            }

            rootObservation
                    .lowCardinalityKeyValue("outcome",
                            decision.decision().name())
                    .lowCardinalityKeyValue("riskBucket",
                            getRiskBucket(decision.riskScore()))
                    .event(Observation.Event.of("synthesis.complete"));

            log.info("Fraud analysis COMPLETE: txnId={} decision={} " +
                            "score={} confidence={} timeMs={}",
                    request.transactionId(),
                    decision.decision(),
                    decision.riskScore(),
                    decision.confidenceLevel(),
                    decision.analysisTimeMs());

            return decision;

        } catch (Exception e) {
            rootObservation.error(e);
            log.error("Fraud analysis failed: txnId={} error={}",
                    request.transactionId(), e.getMessage(), e);

            return buildFallbackDecision(request, startTime, e);

        } finally {
            timerSample.stop(analysisTimer);
            rootObservation.stop();
        }
    }

    // ════════════════════════════════════════════════════════
    // PHASE 1: PLAN
    // ════════════════════════════════════════════════════════

    private FraudAnalysisPlan executePlanPhase(
            FraudAnalysisRequest request) {

        Observation planObs = Observation.createNotStarted(
                "fraud.planning", observationRegistry).start();

        try (Observation.Scope scope = planObs.openScope()) {

            String planningPrompt = buildPlanningPrompt(request);

            FraudAnalysisPlan plan = planningClient.prompt()
                    .user(planningPrompt)
                    .call()
                    .entity(FraudAnalysisPlan.class);

            planObs.highCardinalityKeyValue(
                    "toolsPlanned",
                    String.valueOf(plan.steps().size()));

            return plan;

        } catch (Exception e) {
            planObs.error(e);
            log.warn("Plan phase failed, using default plan: {}",
                    e.getMessage());
            return buildDefaultPlan(request);
        } finally {
            planObs.stop();
        }
    }

    // ════════════════════════════════════════════════════════
    // PHASE 2: EXECUTE TOOLS (ReAct loop with Structured Concurrency)
    // ════════════════════════════════════════════════════════

    private List<Message> executeToolPhase(
            FraudAnalysisRequest request,
            FraudAnalysisPlan plan) {

        Observation execObs = Observation.createNotStarted(
                "fraud.execution", observationRegistry).start();

        List<Message> history = new ArrayList<>();
        history.add(new UserMessage(buildAnalysisPrompt(request)));

        int stepCount = 0;

        try (Observation.Scope scope = execObs.openScope()) {

            List<List<FraudAnalysisPlan.ToolExecutionStep>>
                    executionBatches = groupIntoBatches(plan.steps());

            for (List<FraudAnalysisPlan.ToolExecutionStep> batch :
                    executionBatches) {

                if (stepCount >= MAX_TOOL_CALL_STEPS) {
                    log.warn("Max tool call steps reached: txnId={}",
                            request.transactionId());
                    break;
                }

                if (batch.size() > 1) {
                    List<ToolResult> results =
                            executeToolBatchParallel(batch, request, history);
                    results.forEach(r -> addToolResultToHistory(history, r));
                } else {
                    ToolResult result = executeToolSingle(
                            batch.get(0), request, history);
                    addToolResultToHistory(history, result);
                }

                stepCount += batch.size();

                Optional<List<FraudAnalysisPlan.ToolExecutionStep>>
                        additionalSteps = checkForAdditionalTools(
                        history, stepCount, MAX_TOOL_CALL_STEPS);

                additionalSteps.ifPresent(plan.steps()::addAll);
            }

            execObs.highCardinalityKeyValue(
                    "toolsExecuted", String.valueOf(stepCount));

            return history;

        } finally {
            execObs.stop();
        }
    }

    /**
     * Execute a batch of tools in parallel using Java 25
     * Structured Concurrency (JEP 505, 5th preview).
     *
     * Java 25 API: StructuredTaskScope.open(Joiner) replaces the
     * removed ShutdownOnFailure/ShutdownOnSuccess subclasses.
     *
     * Joiner.allSuccessfulOrThrow():
     *   - Waits for ALL subtasks to succeed
     *   - If ANY subtask fails → cancels scope, join() throws FailedException
     *   - join() returns Stream<Subtask<ToolResult>>
     */
    private List<ToolResult> executeToolBatchParallel(
            List<FraudAnalysisPlan.ToolExecutionStep> batch,
            FraudAnalysisRequest request,
            List<Message> history) {

        List<ToolResult> results = new ArrayList<>();

        try (var scope = StructuredTaskScope.open(
                Joiner.<ToolResult>allSuccessfulOrThrow())) {

            // Fork each tool onto its own Virtual Thread
            for (FraudAnalysisPlan.ToolExecutionStep step : batch) {
                scope.fork(() ->
                        executeToolSingle(step, request, history));
            }

            // join() blocks until all subtasks complete or one fails
            // Returns Stream<Subtask<ToolResult>> on success
            // Throws StructuredTaskScope.FailedException on failure
            scope.join()
                    .map(Subtask::get)
                    .forEach(results::add);

        } catch (StructuredTaskScope.FailedException e) {
            log.warn("Parallel tool batch failed (FailedException), " +
                            "continuing with partial results: {}",
                    e.getCause() != null
                            ? e.getCause().getMessage()
                            : e.getMessage());
            batch.forEach(step -> results.add(
                    ToolResult.failure(step.toolName(),
                            "PARALLEL_EXECUTION_FAILED: " +
                                    (e.getCause() != null
                                            ? e.getCause().getMessage()
                                            : e.getMessage()))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Parallel tool batch interrupted: {}",
                    e.getMessage());
            batch.forEach(step -> results.add(
                    ToolResult.failure(step.toolName(),
                            "PARALLEL_EXECUTION_INTERRUPTED")));
        }

        return results;
    }

    /**
     * Execute a single tool call with its own Micrometer span.
     * This mirrors the SecurityReviewAgentVision pattern exactly.
     */
    private ToolResult executeToolSingle(
            FraudAnalysisPlan.ToolExecutionStep step,
            FraudAnalysisRequest request,
            List<Message> history) {

        Instant toolStart = Instant.now();

        Observation toolObs = Observation.createNotStarted(
                        "fraud.tool." + step.toolName(), observationRegistry)
                .start();

        try (Observation.Scope scope = toolObs.openScope()) {

            log.debug("Executing tool: {} for txnId={}",
                    step.toolName(), request.transactionId());

            ChatResponse response = agentClient.prompt()
                    .messages(history)
                    .user("Execute tool: " + step.toolName() +
                            " with context: " + String.join(", ",
                            step.toolArguments()))
                    .call()
                    .chatResponse();

            long durationMs = Instant.now().toEpochMilli() -
                    toolStart.toEpochMilli();

            String resultContent = extractToolResult(response);

            toolObs.event(Observation.Event.of(
                    step.toolName() + ".success"));

            log.debug("Tool {} completed in {}ms: {}",
                    step.toolName(), durationMs,
                    truncate(resultContent, 200));

            return new ToolResult(step.toolName(), resultContent,
                    durationMs, true);

        } catch (Exception e) {
            long durationMs = Instant.now().toEpochMilli() -
                    toolStart.toEpochMilli();

            toolObs.error(e);
            log.warn("Tool {} failed: {}", step.toolName(),
                    e.getMessage());

            return ToolResult.failure(step.toolName(), e.getMessage());

        } finally {
            toolObs.stop();
        }
    }

    // ════════════════════════════════════════════════════════
    // PHASE 3: SYNTHESIS
    // ════════════════════════════════════════════════════════

    private FraudDecision executeSynthesisPhase(
            FraudAnalysisRequest request,
            List<Message> conversationHistory,
            Instant startTime) {

        Observation synthObs = Observation.createNotStarted(
                "fraud.synthesis", observationRegistry).start();

        try (Observation.Scope scope = synthObs.openScope()) {

            String synthesisPrompt = buildSynthesisPrompt(request);

            List<Message> fullContext =
                    new ArrayList<>(conversationHistory);
            fullContext.add(new UserMessage(synthesisPrompt));

            FraudDecision decision = synthesisClient.prompt()
                    .messages(fullContext)
                    .call()
                    .entity(FraudDecision.class);

            Instant completedAt = Instant.now();
            long analysisTimeMs = completedAt.toEpochMilli() -
                    startTime.toEpochMilli();

            FraudDecision enriched = enrichWithTiming(decision,
                    startTime, completedAt, analysisTimeMs);

            synthObs.highCardinalityKeyValue(
                    "confidenceLevel",
                    enriched.confidenceLevel() != null
                            ? enriched.confidenceLevel().toPlainString()
                            : "unknown");

            return enriched;

        } catch (Exception e) {
            synthObs.error(e);
            throw new com.nexus.fraud.domain.exception
                    .FraudAnalysisException(
                    "Synthesis phase failed", e);
        } finally {
            synthObs.stop();
        }
    }

    // ════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════

    private String buildPlanningPrompt(FraudAnalysisRequest req) {
        return String.format("""
            Analyze this transaction and create an execution plan:

            Transaction ID: %s
            Type: %s
            Amount: %s %s
            Source Account: %s
            Target Account: %s
            Source IP: %s
            Device Fingerprint: %s (known: %s)
            Merchant: %s (MCC: %s)
            Description: %s

            Pre-computed signals:
            %s

            Determine which tools to call and in what order.
            Return ONLY valid FraudAnalysisPlan JSON.
            """,
                req.transactionId(),
                req.transactionType(),
                req.amount(), req.currency(),
                req.sourceAccountId(),
                req.targetAccountId() != null
                        ? req.targetAccountId() : "external",
                req.sourceIp(),
                req.deviceFingerprint() != null ? "present" : "absent",
                req.isKnownDevice(),
                req.merchantName() != null ? req.merchantName() : "N/A",
                req.merchantCategoryCode() != null
                        ? req.merchantCategoryCode() : "N/A",
                req.description() != null ? req.description() : "N/A",
                formatPreSignals(req.preComputedSignals())
        );
    }

    private String buildAnalysisPrompt(FraudAnalysisRequest req) {
        return String.format("""
            Analyze this financial transaction for fraud risk.

            TRANSACTION DETAILS:
            ID: %s | Type: %s | Amount: %s %s
            Source: %s | Target: %s
            IP: %s | Device: %s

            USER CONTEXT:
            User ID: %s
            Account age: %s days
            Total transactions: %s

            MERCHANT:
            Name: %s | MCC: %s

            PRE-COMPUTED SIGNALS:
            %s

            Execute the planned tools and analyze all evidence.
            """,
                req.transactionId(), req.transactionType(),
                req.amount(), req.currency(),
                req.sourceAccountId(),
                req.targetAccountId() != null
                        ? req.targetAccountId() : "EXTERNAL",
                req.sourceIp(),
                req.deviceFingerprint() != null ? "present" : "absent",
                req.userId(),
                req.accountAgeDays(),
                req.totalTransactionCount(),
                req.merchantName() != null ? req.merchantName() : "N/A",
                req.merchantCategoryCode() != null
                        ? req.merchantCategoryCode() : "N/A",
                formatPreSignals(req.preComputedSignals())
        );
    }

    private String buildSynthesisPrompt(FraudAnalysisRequest req) {
        return String.format("""
            Based on ALL tool results in this conversation,
            produce a final FraudDecision for transaction %s.

            Apply the scoring guide:
            - impossible_travel=true: +50 points
            - blacklisted merchant: +100 (auto-REJECT)
            - high_velocity > 5 txn/5min: +35 points
            - VPN: +15 | Tor exit: +50 | Datacenter IP: +20
            - Unknown device + new country: +25 points
            - Amount Z-score > 3: +15 points
            - New counterparty (first transaction): +10 points

            Cite specific policy sections.
            Calculate exact risk score (0-100).
            Return ONLY valid FraudDecision JSON.
            """,
                req.transactionId());
    }

    private FraudDecision buildFallbackDecision(
            FraudAnalysisRequest request,
            Instant startTime, Exception cause) {

        Instant now = Instant.now();
        return new FraudDecision(
                request.transactionId(),
                FraudDecisionOutcome.REVIEW,
                new BigDecimal("50"),
                new BigDecimal("0.3"),
                List.of(new FraudDecision.TriggeringFactor(
                        "SYSTEM_ERROR",
                        "Analysis failed: " + cause.getMessage(),
                        BigDecimal.ONE,
                        "exception: " + cause.getClass().getSimpleName(),
                        null)),
                List.of(),
                "Automated analysis failed. Manual review required. " +
                        "Cause: " + cause.getMessage(),
                List.of(),
                List.of(),
                RecommendedAction.ESCALATE_TO_COMPLIANCE,
                "HIGH",
                Map.of("error", cause.getMessage()),
                startTime, now,
                now.toEpochMilli() - startTime.toEpochMilli()
        );
    }

    private FraudDecision enrichWithTiming(FraudDecision d,
                                           Instant start,
                                           Instant completed,
                                           long ms) {
        return new FraudDecision(
                d.transactionId(), d.decision(),
                d.riskScore(), d.confidenceLevel(),
                d.triggeringFactors(), d.clearingFactors(),
                d.reasoning(), d.policyCitations(),
                d.toolCallSummary(), d.recommendedAction(),
                d.reviewPriority(), d.rawSignals(),
                start, completed, ms
        );
    }

    private FraudAnalysisPlan buildDefaultPlan(
            FraudAnalysisRequest req) {
        return new FraudAnalysisPlan(
                new ArrayList<>(List.of(
                        new FraudAnalysisPlan.ToolExecutionStep(
                                1, "velocity_check_tool",
                                List.of(req.userId()), true,
                                "Check transaction velocity", "MANDATORY"),
                        new FraudAnalysisPlan.ToolExecutionStep(
                                1, "geolocation_anomaly_tool",
                                List.of(req.sourceIp()), true,
                                "Check geolocation", "IP available"),
                        new FraudAnalysisPlan.ToolExecutionStep(
                                2, "rag_policy_tool",
                                List.of("general fraud"), false,
                                "Retrieve policies", "MANDATORY")
                )),
                "Unable to generate plan, using defaults",
                List.of("plan_generation_failed"),
                "Default plan due to planning phase failure"
        );
    }

    /**
     * Groups sequential steps into batches.
     * Steps marked canRunInParallel=true at the same step number
     * are grouped together for parallel execution.
     */
    private List<List<FraudAnalysisPlan.ToolExecutionStep>>
    groupIntoBatches(
            List<FraudAnalysisPlan.ToolExecutionStep> steps) {

        List<List<FraudAnalysisPlan.ToolExecutionStep>> batches =
                new ArrayList<>();
        List<FraudAnalysisPlan.ToolExecutionStep> currentBatch =
                new ArrayList<>();

        for (FraudAnalysisPlan.ToolExecutionStep step : steps) {
            if (step.canRunInParallel() && !currentBatch.isEmpty()
                    && currentBatch.get(0).canRunInParallel()) {
                currentBatch.add(step);
            } else {
                if (!currentBatch.isEmpty()) {
                    batches.add(new ArrayList<>(currentBatch));
                }
                currentBatch = new ArrayList<>();
                currentBatch.add(step);
            }
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    private void addToolResultToHistory(List<Message> history,
                                        ToolResult result) {
        history.add(new AssistantMessage(
                "Tool " + result.toolName() + " executed. " +
                        (result.succeeded()
                                ? "Result: " + result.content()
                                : "FAILED: " + result.content())));
    }

    private Optional<List<FraudAnalysisPlan.ToolExecutionStep>>
    checkForAdditionalTools(List<Message> history,
                            int stepCount, int maxSteps) {
        if (stepCount >= maxSteps - 1) return Optional.empty();
        return Optional.empty();
    }

    private String extractToolResult(ChatResponse response) {
        if (response.getResult() != null &&
                response.getResult().getOutput() != null) {
            return response.getResult().getOutput().getText();
        }
        return "NO_RESULT";
    }

    private String formatPreSignals(Map<String, Object> signals) {
        if (signals == null || signals.isEmpty()) {
            return "No pre-computed signals available";
        }
        StringBuilder sb = new StringBuilder();
        signals.forEach((k, v) -> sb.append("  ").append(k)
                .append(": ").append(v).append("\n"));
        return sb.toString();
    }

    private String getRiskBucket(BigDecimal score) {
        if (score == null) return "unknown";
        int s = score.intValue();
        if (s < 30) return "0-29";
        if (s < 70) return "30-69";
        return "70-100";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Inner result record for tool execution outcomes. */
    public record ToolResult(
            String toolName, String content,
            long durationMs, boolean succeeded
    ) {
        static ToolResult failure(String tool, String error) {
            return new ToolResult(tool, error, 0, false);
        }
    }
}