package com.nexus.transaction.infrastructure.elasticsearch;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Elasticsearch repository for full-text transaction search.
 * Supports search by description and merchantName scoped to userId.
 */
@Repository
public interface TransactionSearchRepository
        extends ElasticsearchRepository<TransactionSearchDocument, String> {

    List<TransactionSearchDocument> findByUserIdAndDescriptionContainingOrMerchantNameContaining(
            String userId, String descriptionQuery, String merchantQuery);

    List<TransactionSearchDocument> findByUserIdOrderByInitiatedAtDesc(String userId);

    List<TransactionSearchDocument> findByUserIdAndStatus(String userId, String status);

    List<TransactionSearchDocument> findByUserIdAndTransactionType(String userId, String transactionType);
}