package com.nexus.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Optional;

/**
 * JPA Configuration — persistence layer setup.
 *
 * Key decisions:
 *
 * @EnableJpaAuditing — auto-populates @CreatedDate / @LastModifiedDate
 *   on entity @PrePersist/@PreUpdate. AuditLog uses Instant directly
 *   (not Spring Data audit annotations) for immutability control.
 *
 * @EnableTransactionManagement — Spring manages @Transactional boundaries.
 *   UserCommandService uses @Transactional to ensure domain write +
 *   outbox write are atomic (Outbox Pattern guarantee).
 *
 * @EnableJpaRepositories — scoped to identity service packages only.
 *   Prevents accidental cross-service repository scanning in tests.
 *
 * Hibernate batch:
 *   batch_size=20, order_inserts=true, order_updates=true
 *   These are set in application.yml. Reduces N+1 round trips
 *   when saving multiple sessions or audit entries.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
@EnableJpaRepositories(
        basePackages = "com.nexus.identity.infrastructure.persistence")
@EnableTransactionManagement
public class JpaConfig {

    /**
     * AuditorAware — provides the "current user" for @CreatedBy fields.
     *
     * For the identity service, most writes are unauthenticated
     * (registration, login) or system-initiated (SAGA compensation).
     * Returns empty — Spring Security context may not be populated
     * during these flows.
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            try {
                var auth = org.springframework.security.core.context
                        .SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated()
                        && !"anonymousUser".equals(auth.getPrincipal())) {
                    return Optional.of(auth.getName());
                }
            } catch (Exception ignored) {}
            return Optional.of("system");
        };
    }
}