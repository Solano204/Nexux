package com.nexus.account.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoConfig — MongoDB configuration for account analytics.
 *
 * MongoDB stores pre-aggregated analytics documents:
 * - AccountAnalyticsDocument: spending patterns, category breakdowns,
 *   monthly trends, savings opportunities, recurring transactions,
 *   and risk indicators.
 *
 * These are CQRS read-side projections — optimized for fast
 * single-document reads by the AI advisor and dashboard endpoints.
 *
 * Collection: account_analytics
 * Indexes: accountId (unique), userId
 *
 * Connection is configured in application.yml via:
 *   spring.data.mongodb.uri
 *
 * Auto-index creation is enabled in application.yml.
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "com.nexus.account.infrastructure.mongodb")
public class MongoConfig {
    // Spring Boot auto-configures MongoClient from application.yml
    // Custom converters can be added here if needed via:
    //   @Bean MongoCustomConversions customConversions() { ... }
}