package com.nexus.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA Configuration — Ledger Service.
 *
 * SERIALIZABLE isolation is set per-method in LedgerCommandService,
 * not globally here. Read-only queries use default READ_COMMITTED.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.nexus.ledger.infrastructure.persistence")
public class JpaConfig {
}