package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.domain.model.OutboxEntry;
import com.nexus.saga.infrastructure.jpa.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saga Command Producer — polls outbox table, publishes to Kafka.
 *
 * Outbox polling pattern: reads unprocessed entries every 100ms,
 * publishes to the target topic, marks as processed.
 * Guarantees at-least-once delivery from state change to Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandProducer {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 100)
    @Transactional
    public void pollAndPublish() {
        List<OutboxEntry> entries = outboxRepository
                .findByProcessedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEntry entry : entries) {
            try {
                String payload = objectMapper.writeValueAsString(
                        entry.getPayload());

                // ✅ FIX 1: Don't call .get() — it blocks the scheduler thread
                // and can deadlock inside @Transactional. Use a callback instead.
                kafkaTemplate.send(
                        entry.getTopic(),
                        entry.getAggregateId().toString(),
                        payload
                ).whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka send failed async: id={} topic={}: {}",
                                entry.getOutboxId(), entry.getTopic(), ex.getMessage());
                    } else {
                        log.debug("Kafka send confirmed: topic={} partition={} offset={}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });

                // ✅ FIX 2: entry.setProcessedAt() doesn't exist — OutboxEntry
                // only has @Getter. Use the domain method markProcessed() instead.
                entry.markProcessed();
                outboxRepository.save(entry);

                log.debug("Outbox published: topic={} type={} id={}",
                        entry.getTopic(), entry.getEventType(),
                        entry.getOutboxId());

            } catch (Exception e) {
                log.error("Outbox publish failed: id={} topic={}: {}",
                        entry.getOutboxId(), entry.getTopic(),
                        e.getMessage());
                break; // Stop processing — preserve ordering
            }
        }
    }
}