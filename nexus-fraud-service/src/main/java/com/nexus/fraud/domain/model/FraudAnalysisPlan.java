package com.nexus.fraud.domain.model;

import java.util.List;

/**
 * FraudAnalysisPlan — Output of the PLAN phase.
 *
 * The LLM produces this structured plan before any tools are called.
 * It determines which tools to call, in what order, and which can
 * run in parallel (Structured Concurrency).
 *
 * Pattern: Plan-then-Act (Section 11)
 */
public record FraudAnalysisPlan(
        List<ToolExecutionStep> steps,
        String initialRiskAssessment,
        List<String> primaryConcerns,
        String planningRationale
) {

    public record ToolExecutionStep(
            int stepNumber,
            String toolName,
            List<String> toolArguments,
            /** If true: run concurrently with adjacent parallel steps */
            boolean canRunInParallel,
            String expectedOutcome,
            String relevanceReason
    ) {}
}