package com.nexus.risk.domain.exception;

/**
 * RiskScoringException — thrown when risk profile computation fails.
 *
 * Causes: AI provider unavailable, tool execution failure,
 * data gaps preventing minimum confidence threshold,
 * timeout during Plan-then-Act agent execution.
 *
 * Non-fatal for batch: NightlyRiskScoringJob catches and counts failures.
 * Fatal for event-triggered: logged + metric incremented, user retried next cycle.
 */
public class RiskScoringException extends RuntimeException {

    public RiskScoringException(String message) {
        super(message);
    }

    public RiskScoringException(String message, Throwable cause) {
        super(message, cause);
    }
}