package com.nexus.analytics.infrastructure.elasticsearch;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Analytics Document Elasticsearch Repository.
 *
 * Queries use routing=userId for shard-level efficiency.
 */
@Repository
public interface AnalyticsDocumentRepository
        extends ElasticsearchRepository<AnalyticsDocument, String> {

    Optional<AnalyticsDocument> findByUserIdAndPeriodTypeAndYearAndMonth(
            String userId, String periodType, int year, int month);

    List<AnalyticsDocument> findByUserIdAndPeriodTypeOrderByYearDescMonthDesc(
            String userId, String periodType);

    List<AnalyticsDocument> findByUserIdAndYearAndMonth(
            String userId, int year, int month);
}