package com.nexus.risk.agent.model;

import java.util.List;
import java.util.Map;

/**
 * RiskScoringPlan — Section 3 structured output from planning phase.
 *
 * The agent generates this plan before executing any tools.
 * Plan varies by user: new users get SHALLOW, long-term users get DEEP.
 * parallelGroups: lists of step numbers that can run concurrently.
 */
public record RiskScoringPlan(
        List<RiskScoringStep> steps,
        List<List<Integer>> parallelGroups,
        String planRationale,
        String expectedAnalysisDepth,  // SHALLOW, STANDARD, DEEP, COMPREHENSIVE
        int estimatedDurationSeconds
) {

    public record RiskScoringStep(
            int stepNumber,
            String toolName,
            Map<String, Object> toolArguments,
            boolean canParallel,
            boolean isMandatory,
            String dataPointsExpected,
            String relevanceReason
    ) {}
}
