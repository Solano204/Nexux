package com.nexus.fraud.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA Configuration — Fraud Service.
 *
 * Enables auditing, repository scanning for FraudDecisionRepository
 * + OutboxRepository, and transaction management for
 * the @Transactional in FraudAnalysisService.
 */
@Configuration
@EnableJpaAuditing
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.nexus.fraud.infrastructure.persistence")
public class JpaConfig {
}