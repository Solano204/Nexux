package com.nexus.ledger.application.maintenance;

import com.nexus.ledger.infrastructure.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Purges the outbox table (retention: 7 days, matching what
 * OutboxRepository's Javadoc already documented but nothing actually
 * enforced - see CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md
 * Section 3). Without this, `outbox` grows unbounded forever - Debezium
 * reads the WAL, it never deletes the row that generated the WAL entry.
 *
 * Same @Scheduled cron convention as ReconciliationJobService (this
 * package's neighbor) - runs during the same low-traffic window, offset
 * by an hour so the two jobs don't compete for I/O on the same tables.
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
        this.deletedCounter = Counter.builder("ledger.outbox.cleanup.deleted.total")
                .description("Outbox rows deleted by the retention cleanup job")
                .register(meterRegistry);
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "America/Mexico_City")
    @Transactional
    public void purgeOldEntries() {
        Instant threshold = Instant.now().minus(RETENTION);
        int deleted = outboxRepository.deleteEntriesOlderThan(threshold);
        deletedCounter.increment(deleted);
        log.info("Outbox cleanup: deleted {} entries older than {} ({} retention)",
                deleted, threshold, RETENTION);
    }
}
