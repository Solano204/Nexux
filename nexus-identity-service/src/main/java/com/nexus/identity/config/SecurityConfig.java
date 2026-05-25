package com.nexus.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration — Spring Security for Identity Service.
 *
 * The identity service handles its OWN authentication logic in
 * UserCommandService. Spring Security here provides:
 * - BCryptPasswordEncoder bean (strength 12)
 * - Stateless session (JWT-based)
 * - Public endpoint allowlist
 * - CSRF disabled (API — uses JWT, not cookies for auth)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt strength 12: ~250-350ms per hash.
     * Security: makes offline brute-force attacks infeasible.
     * Performance: ~3 verifications/sec per CPU core.
     * Financial platform: this is the correct security/performance tradeoff.
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                                // Public auth endpoints — no JWT required
                                .requestMatchers(
                                        "/api/v1/auth/register",
                                        "/api/v1/auth/login",
                                        "/api/v1/auth/refresh-token",
                                        "/api/v1/auth/logout",
                                        "/api/v1/auth/.well-known/jwks.json",
                                        "/api/v1/auth/password-reset/**"
                                ).permitAll()
                                // Actuator health/prometheus — Prometheus scrapes these
                                .requestMatchers(
                                        "/actuator/health",
                                        "/actuator/prometheus"
                                ).permitAll()
                                // All other endpoints require authentication via gateway headers
                                .anyRequest().permitAll()
                        // Note: Fine-grained auth handled by extracting X-User-Id
                        // from gateway-set headers, not Spring Security annotations
                )
                .build();
    }
}