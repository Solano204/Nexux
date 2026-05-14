package com.nexus.transaction.infrastructure.elasticsearch;

import com.nexus.transaction.domain.model.Transaction;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Elasticsearch Indexing Service — async transaction indexing.
 *
 * Called asynchronously after every status change.
 * Non-blocking: failures are logged but don't fail the transaction.
 * Virtual Thread executor handles the async execution.
 *
 * Uses UPSERT: creates if not exists, updates if exists.
 * This handles both initial indexing and status updates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchIndexingService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final ObservationRegistry observationRegistry;

    private static final String INDEX = "transactions";

    @Async("virtualThreadExecutor")
    public void indexAsync(Transaction txn) {
        Observation obs = Observation.createNotStarted(
                "elasticsearch.index.transaction",
                observationRegistry).start();

        try {
            TransactionSearchDocument doc =
                    TransactionSearchDocument.builder()
                            .transactionId(txn.getTransactionId().toString())
                            .userId(txn.getUserId().toString())
                            .sourceAccountId(
                                    txn.getSourceAccountId().toString())
                            .targetAccountId(txn.getTargetAccountId() != null
                                    ? txn.getTargetAccountId().toString() : null)
                            .amount(txn.getAmount())
                            .currency(txn.getCurrency())
                            .status(txn.getStatus().name())
                            .transactionType(txn.getTransactionType().name())
                            .description(txn.getDescription())
                            .merchantName(txn.getMerchantName())
                            .merchantCategoryCode(txn.getMerchantCategoryCode())
                            .fraudScore(txn.getFraudScore())
                            .fraudDecision(txn.getFraudDecision())
                            .initiatedAt(txn.getInitiatedAt())
                            .completedAt(txn.getCompletedAt())
                            .failureReason(txn.getFailureReason())
                            .build();

            elasticsearchOperations.save(doc,
                    IndexCoordinates.of(INDEX));

            obs.event(Observation.Event.of("es.index.success"));

        } catch (Exception e) {
            obs.error(e);
            // Non-fatal: search index lag is acceptable
            log.warn("Elasticsearch indexing failed for txnId={}: {}",
                    txn.getTransactionId(), e.getMessage());
        } finally {
            obs.stop();
        }
    }
}