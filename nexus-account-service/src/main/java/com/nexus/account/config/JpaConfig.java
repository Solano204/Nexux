package com.nexus.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Optional;

/**
 * JpaConfig — JPA auditing and transaction management.
 *
 * Key configuration:
 * - @EnableJpaAuditing: auto-populates @CreatedDate, @LastModifiedDate
 * - @EnableTransactionManagement: enables @Transactional annotations
 * - @EnableJpaRepositories: scans persistence package for Spring Data repos
 *
 * Hibernate tuning is done in application.yml:
 * - provider_disables_autocommit: true (prevents unnecessary COMMIT calls)
 * - batch_size: 20 (batches INSERT/UPDATE statements)
 * - order_inserts/order_updates: true (enables batch grouping)
 * - lock_timeout: 5000ms (PostgreSQL parameter for SELECT FOR UPDATE)
 *
 * Connection pool (HikariCP):
 * - maximum-pool-size: 50 (large due to SELECT FOR UPDATE holding connections)
 * - minimum-idle: 10
 * - connection-timeout: 30s
 */
@Configuration
@EnableJpaAuditing
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.nexus.account.infrastructure.persistence")
public class JpaConfig {

    /**
     * Auditor provider for @CreatedBy / @LastModifiedBy annotations.
     * Returns "system" since account modifications are service-driven,
     * not user-driven (users go through API Gateway → service → aggregate).
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> Optional.of("nexus-account-service");
    }
}