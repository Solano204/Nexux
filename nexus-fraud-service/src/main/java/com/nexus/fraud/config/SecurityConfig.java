package com.nexus.fraud.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

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
 * Docker network isolation alone was the only real boundary here —
 * some of these endpoints are destructive (merchant blacklist,
 * overriding a fraud review outcome) with no application-layer check
 * at all. Same X-Internal-Service allow-list filter as
 * nexus-account-service's SecurityConfig, as defense in depth in case
 * network isolation is ever misconfigured.
 * In production: mTLS replaces shared headers, same as account-service.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Set<String> ALLOWED_INTERNAL_SERVICES = Set.of(
            "nexus-ai-assistant-service",
            "nexus-saga-orchestrator",
            "nexus-audit-query-jvm",
            "nexus-transaction-service",
            "nexus-api-gateway"
    );

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
                .addFilterBefore(
                        new InternalServiceAuthFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Validates X-Internal-Service against the allow-list for /internal/**
     * paths — same filter as nexus-account-service's SecurityConfig.
     */
    static class InternalServiceAuthFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {

            String path = request.getRequestURI();

            if (path.startsWith("/internal/")) {
                String serviceHeader = request.getHeader("X-Internal-Service");
                if (serviceHeader == null || !ALLOWED_INTERNAL_SERVICES.contains(serviceHeader)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("""
                            {"error": "FORBIDDEN", "message": "Invalid or missing X-Internal-Service header"}
                            """);
                    return;
                }
            }

            filterChain.doFilter(request, response);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return request.getRequestURI().startsWith("/actuator");
        }
    }
}