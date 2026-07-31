package com.nexus.kyc.application.maintenance;

import com.nexus.kyc.infrastructure.jpa.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Purges kyc_outbox (retention: 7 days). Without this it grows unbounded
 * forever - Debezium reads the WAL, it never deletes the row that
 * generated the WAL entry. See CHANGES-BESTPRACTICES/
 * 08_EVENT_DESIGN_CHANGES.md Section 3, and OutboxRepository's Javadoc for
 * why this can't key off processed_at.
 */
@Slf4j
@Component
public class OutboxCleanupJob {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final OutboxRepository outboxRepository;
    private final Counter deletedCounter;

    public OutboxCleanupJob(OutboxRepository outboxRepository,
                            MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.deletedCounter = Counter.builder("kyc.outbox.cleanup.deleted.total")
                .description("Outbox rows deleted by the retention cleanup job")
                .register(meterRegistry);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Mexico_City")
    @Transactional
    public void purgeOldEntries() {
        Instant threshold = Instant.now().minus(RETENTION);
        int deleted = outboxRepository.deleteEntriesOlderThan(threshold);
        deletedCounter.increment(deleted);
        log.info("Outbox cleanup: deleted {} entries older than {} ({} retention)",
                deleted, threshold, RETENTION);
    }
}
