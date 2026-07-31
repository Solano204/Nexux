package com.nexus.identity.application.maintenance;

import com.nexus.identity.infrastructure.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Purges the outbox table (retention: 7 days). Without this, `outbox`
 * grows unbounded forever - Debezium reads the WAL, it never deletes the
 * row that generated the WAL entry. See CHANGES-BESTPRACTICES/
 * 08_EVENT_DESIGN_CHANGES.md Section 3, and OutboxRepository's Javadoc for
 * why this can't key off `processedAt` (that column is never set).
 *
 * nexus-identity-service already has @EnableScheduling on
 * NexusIdentityServiceApplication - no scheduler-registration fix needed
 * here, unlike account-service/transaction-service.
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
        this.deletedCounter = Counter.builder("identity.outbox.cleanup.deleted.total")
                .description("Outbox rows deleted by the retention cleanup job")
                .register(meterRegistry);
    }

    @Scheduled(cron = "0 45 2 * * *", zone = "America/Mexico_City")
    @Transactional
    public void purgeOldEntries() {
        Instant threshold = Instant.now().minus(RETENTION);
        int deleted = outboxRepository.deleteEntriesOlderThan(threshold);
        deletedCounter.increment(deleted);
        log.info("Outbox cleanup: deleted {} entries older than {} ({} retention)",
                deleted, threshold, RETENTION);
    }
}
