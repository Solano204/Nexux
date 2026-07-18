package com.nexus.kyc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration — AI KYC Service (NFP-SVC-009).
 *
 * Internal + user-facing endpoints. Requests arrive via:
 * - Kafka/SQS bridge consumers (no HTTP auth)
 * - Internal service-to-service calls (Identity Service, Saga Orchestrator)
 * - Actuator endpoints (Prometheus, Health Monitor Lambda)
 * - User-facing /api/v1/kyc/** through API Gateway (X-User-Id header)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/api/v1/kyc/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}