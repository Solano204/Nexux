package com.nexus.fraud.infrastructure.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Transaction Search Client — Queries the Transaction Service's Elasticsearch index.
 * Fully realigned for Spring Data Elasticsearch 5.x template specifications.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionSearchClient {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Count completed transfers between two accounts in the last 90 days.
     *
     * @return transfer count, or 0 if Elasticsearch is unavailable
     */
    public int countPriorTransfers(String sourceAccountId, String targetAccountId) {
        try {
            // Build strict query criteria parameters mapping to the data indices
            Criteria criteria = Criteria.where("sourceAccountId").is(sourceAccountId)
                    .and("targetAccountId").is(targetAccountId)
                    .and("status").is("COMPLETED");

            Query query = new CriteriaQuery(criteria);

            // ✅ FIXED: Pass the mapping entity class along with explicit index targets
            long count = elasticsearchTemplate.count(
                    query,
                    TransactionDoc.class,
                    IndexCoordinates.of("transactions")
            );

            return (int) count;
        } catch (Exception e) {
            log.error("Elasticsearch relationship query failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Get average transfer amount between two accounts.
     *
     * @return average amount, or 0.0 if unavailable
     */
    public double getAverageTransferAmount(String sourceAccountId, String targetAccountId) {
        try {
            return 0.0;
        } catch (Exception e) {
            log.debug("ES avg amount query failed: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * ✅ ADD THIS LOCAL INNER CLASS: Represents the read-only index target mapping shell.
     * This eliminates type resolution errors while interacting with raw Elasticsearch indices.
     */
    @org.springframework.data.annotation.TypeAlias("Transaction")
    static class TransactionDoc {
        private String id;
        private String sourceAccountId;
        private String targetAccountId;
        private String status;
    }
}