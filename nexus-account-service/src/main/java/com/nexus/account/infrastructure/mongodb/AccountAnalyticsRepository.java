package com.nexus.account.infrastructure.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AccountAnalyticsRepository — MongoDB data access for analytics.
 *
 * Pre-aggregated analytics documents, one per account.
 * Read-optimized: single document read per account for
 * dashboard and AI advisor context enrichment.
 *
 * Write operations are performed by:
 * - Analytics aggregation scheduled job (periodic)
 * - AccountCommandService.initAnalyticsDocument (on account creation)
 * - AI Advisor service (stores savingsOpportunities after analysis)
 */
@Repository
public interface AccountAnalyticsRepository
        extends MongoRepository<AccountAnalyticsDocument, String> {

    /**
     * Find analytics by accountId (unique index).
     * Primary lookup — used by AccountQueryService and AI Advisor.
     */
    Optional<AccountAnalyticsDocument> findByAccountId(String accountId);

    /**
     * All analytics documents for a user's accounts.
     * Used by the portfolio/dashboard view.
     */
    List<AccountAnalyticsDocument> findByUserId(String userId);

    /**
     * Documents that haven't been updated recently — need refresh.
     * Used by the analytics aggregation scheduled job.
     */
    @Query("{ 'lastUpdated': { $lt: ?0 } }")
    List<AccountAnalyticsDocument> findStaleDocuments(Instant threshold);

    /**
     * Documents that haven't had an AI advisor run recently.
     * Used to schedule proactive advice generation.
     */
    @Query("{ $or: [ { 'lastAdvisorRun': { $lt: ?0 } }, { 'lastAdvisorRun': null } ] }")
    List<AccountAnalyticsDocument> findDueForAdvisorRun(Instant threshold);

    /**
     * Delete analytics when account is closed.
     */
    void deleteByAccountId(String accountId);

    /**
     * Check if analytics exist for an account.
     */
    boolean existsByAccountId(String accountId);
}