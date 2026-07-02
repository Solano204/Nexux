package com.nexus.risk.application.batch;

import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Nightly Risk Scoring Job Trigger Service.
 *
 * Provides manual trigger + status checking for the batch job.
 * Used by InternalRiskController for:
 * - POST /internal/v1/risk/batch/trigger → manual batch start
 * - GET /internal/v1/risk/batch/status → current batch status
 * - GET /internal/v1/risk/recomputation-candidates → users needing scoring
 *
 * Separated from NightlyRiskScoringJob to keep the @Scheduled
 * cron job clean and allow independent manual triggering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NightlyRiskScoringJobTriggerService {

    private final NightlyRiskScoringJob nightlyJob;
    private final RiskProfileRepository profileRepository;

    private volatile boolean batchRunning = false;
    private volatile Instant lastBatchStart = null;
    private volatile Instant lastBatchComplete = null;

    /**
     * Manually trigger the nightly batch outside the cron schedule.
     * Used for: testing, catch-up after outage, ad-hoc recomputation.
     * Returns immediately — batch runs async on virtual thread.
     */
    public Map<String, Object> triggerManualBatch() {
        if (batchRunning) {
            return Map.of(
                    "status", "ALREADY_RUNNING",
                    "startedAt", lastBatchStart != null
                            ? lastBatchStart.toString() : "unknown",
                    "message", "A batch is already in progress");
        }

        batchRunning = true;
        lastBatchStart = Instant.now();

        Thread.startVirtualThread(() -> {
            try {
                nightlyJob.runNightlyBatch();
            } finally {
                batchRunning = false;
                lastBatchComplete = Instant.now();
            }
        });

        return Map.of(
                "status", "TRIGGERED",
                "startedAt", lastBatchStart.toString(),
                "message", "Manual batch triggered successfully");
    }

    /**
     * Current batch status.
     */
    public Map<String, Object> getBatchStatus() {
        return Map.of(
                "isRunning", batchRunning,
                "lastBatchStart", lastBatchStart != null
                        ? lastBatchStart.toString() : "never",
                "lastBatchComplete", lastBatchComplete != null
                        ? lastBatchComplete.toString() : "never");
    }

    /**
     * Users that would be scored in the next batch run.
     */
    public List<String> getRecomputationCandidates() {
        return profileRepository.findUsersNeedingRecomputation(
                Instant.now().minus(Duration.ofHours(20)));
    }
}