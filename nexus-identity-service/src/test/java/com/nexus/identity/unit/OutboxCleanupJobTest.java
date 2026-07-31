package com.nexus.identity.unit;

import com.nexus.identity.application.maintenance.OutboxCleanupJob;
import com.nexus.identity.infrastructure.persistence.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxCleanupJobTest {

    @Mock private OutboxRepository outboxRepository;

    private OutboxCleanupJob job;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        job = new OutboxCleanupJob(outboxRepository, meterRegistry);
    }

    @Test
    void purgeDeletesEntriesOlderThanSevenDaysAndIncrementsCounter() {
        when(outboxRepository.deleteEntriesOlderThan(any(Instant.class))).thenReturn(42);

        job.purgeOldEntries();

        verify(outboxRepository).deleteEntriesOlderThan(any(Instant.class));
        assertThat(meterRegistry.counter("identity.outbox.cleanup.deleted.total").count()).isEqualTo(42.0);
    }

    @Test
    void purgeUsesThresholdApproximatelySevenDaysInThePast() {
        when(outboxRepository.deleteEntriesOlderThan(any(Instant.class))).thenReturn(0);

        job.purgeOldEntries();

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).deleteEntriesOlderThan(captor.capture());
        long daysAgo = java.time.Duration.between(captor.getValue(), Instant.now()).toDays();
        assertThat(daysAgo).isBetween(6L, 7L);
    }

    @Test
    void purgeHandlesZeroDeletionsGracefully() {
        when(outboxRepository.deleteEntriesOlderThan(any(Instant.class))).thenReturn(0);

        job.purgeOldEntries();

        assertThat(meterRegistry.counter("identity.outbox.cleanup.deleted.total").count()).isEqualTo(0.0);
    }
}
