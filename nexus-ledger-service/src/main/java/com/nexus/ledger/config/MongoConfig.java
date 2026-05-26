package com.nexus.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB Configuration — CQRS read model.
 * account_ledger_summary + posting_documents collections.
 */
@Configuration
@EnableMongoRepositories(basePackages = "com.nexus.ledger.infrastructure.mongodb")
public class MongoConfig {
}