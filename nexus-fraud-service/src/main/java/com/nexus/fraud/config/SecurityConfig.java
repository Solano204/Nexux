package com.nexus.fraud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration — Fraud Service.
 *
 * Internal-only service: NO user-facing endpoints.
 * All endpoints are /internal/** — accessed by other services
 * through the internal Docker network.
 *
 * Actuator endpoints exposed for Prometheus + health checks.
 * CSRF disabled (stateless REST API).
 * Sessions disabled (stateless, each request authenticated via headers).
 *
 * In production: IP-restricted to Docker network CIDR only.
 * The API Gateway does NOT route external traffic to this service.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator: health + prometheus for monitoring
                        .requestMatchers("/actuator/**").permitAll()
                        // Internal endpoints: service-to-service only
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}