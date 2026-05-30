package com.nexus.risk.application.batch;

// ✅ FIX: RiskScoringAgent lives in com.nexus.risk.agent — different package,
//         so it must be imported explicitly. Without this line the compiler
//         has never heard of RiskScoringAgent, which causes BOTH errors:
//           - Cannot resolve symbol 'RiskScoringAgent'
//           - Cannot resolve method 'computeRiskProfile(String, String)'

import com.nexus.risk.agent.model.RiskScoringAgent;
import com.nexus.risk.infrastructure.jpa.RiskProfileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Nightly Risk Scoring Batch Job.
 *
 * Runs at 2am Mexico City time. Processes all users needing recomputation.
 * Virtual Thread executor: hundreds of concurrent risk computations.
 * Semaphore: limits concurrent AI calls (OpenAI rate limit: ~55 RPM).
 *
 * Each user computation:
 *   Plan:           2–5 s
 *   Tool execution: 10–40 s  (parallel where possible)
 *   Synthesis:      5–15 s
 *   Total:          15–60 s per user
 *
 * At 50 concurrent computations → ~3,000 users/hour.
 * Batch window 2am–6am = 4 hours = 12,000 users max.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NightlyRiskScoringJob {

    private final RiskScoringAgent riskScoringAgent;   // ✅ resolves now
    private final RiskProfileRepository profileRepository;
    private final ObservationRegistry   observationRegistry;
    private final MeterRegistry         meterRegistry;

    private static final int MAX_CONCURRENT = 50;

    @Scheduled(cron = "0 0 2 * * *", zone = "America/Mexico_City")
    public void runNightlyBatch() {

        String  jobId     = UUID.randomUUID().toString();
        Instant jobStart  = Instant.now();

        log.info("Nightly risk scoring batch started: jobId={}", jobId);

        // All users needing recomputation:
        //   - no profile exists, OR
        //   - existing profile is older than 20 hours
        List<String> usersToScore = profileRepository
                .findUsersNeedingRecomputation(
                        Instant.now().minus(Duration.ofHours(20)));

        log.info("Users to score: {}", usersToScore.size());
        recordJobStart(jobId, usersToScore.size());

        // One virtual thread per user — no pool sizing needed
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Rate-limit: at most MAX_CONCURRENT AI calls in flight at once
        Semaphore limiter = new Semaphore(MAX_CONCURRENT);

        // ✅ computeRiskProfile(String, String) resolves correctly now that
        //    the import above makes RiskScoringAgent visible to the compiler.
        List<Future<Boolean>> futures = usersToScore.stream()
                .map(userId -> executor.submit(() -> {
                    limiter.acquire();
                    try {
                        riskScoringAgent.computeRiskProfile(userId, "SCHEDULED");
                        return true;
                    } catch (Exception e) {
                        log.warn("Risk scoring failed: userId={} {}",
                                userId, e.getMessage());
                        return false;
                    } finally {
                        limiter.release();
                    }
                }))
                .toList();

        int completed = 0, failed = 0;

        for (int i = 0; i < futures.size(); i++) {
            try {
                boolean success = futures.get(i).get(120, TimeUnit.SECONDS);
                if (success) completed++; else failed++;
            } catch (TimeoutException e) {
                log.warn("Timeout for user index {}", i);
                failed++;
            } catch (Exception e) {
                log.error("Future failed: {}", e.getMessage());
                failed++;
            }

            if ((i + 1) % 100 == 0) {
                log.info("Batch progress: {}/{} completed={} failed={}",
                        i + 1, usersToScore.size(), completed, failed);
            }
        }

        executor.shutdown();

        long durationMs = System.currentTimeMillis() - jobStart.toEpochMilli();

        log.info("Nightly batch complete: jobId={} total={} completed={} failed={} durationMs={}",
                jobId, usersToScore.size(), completed, failed, durationMs);

        recordJobComplete(jobId, completed, failed, durationMs);
    }

    private void recordJobStart(String jobId, int usersScheduled) {
        meterRegistry.counter("risk.batch.job.started").increment();
        log.info("Job {} started with {} users", jobId, usersScheduled);
    }

    private void recordJobComplete(String jobId, int completed,
                                   int failed, long durationMs) {
        meterRegistry.counter("risk.batch.job.completed").increment();
        meterRegistry.timer("risk.batch.job.duration.seconds")
                .record(durationMs, TimeUnit.MILLISECONDS);
        log.info("Job {} done: completed={} failed={} ms={}",
                jobId, completed, failed, durationMs);
    }
}