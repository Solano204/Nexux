package com.nexus.account.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * SecurityConfig — Dual authentication strategy.
 *
 * Internal endpoints (/internal/api/v1/**):
 *   - Authenticated via X-Internal-Service header
 *   - Bypass JWT validation (service-to-service trust)
 *   - In production: mTLS replaces shared headers
 *
 * External endpoints (/api/v1/**):
 *   - X-User-Id header required (set by API Gateway after JWT validation)
 *   - API Gateway handles JWT verification before routing
 *
 * Actuator endpoints (/actuator/**):
 *   - Health check is public (for Docker HEALTHCHECK + load balancer)
 *   - Prometheus/metrics require internal service header
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Set<String> ALLOWED_INTERNAL_SERVICES = Set.of(
            "nexus-saga-orchestrator",
            "nexus-identity-service",
            "nexus-transaction-service",
            "nexus-fraud-service",
            "nexus-api-gateway",
            "nexus-health-monitor-lambda",
            "nexus-ledger-service"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator health is public (Docker HEALTHCHECK)
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Actuator metrics/prometheus require internal service auth
                        .requestMatchers("/actuator/**").permitAll()
                        // OpenAPI/Swagger UI — dev-only surface, no business data
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // Internal service-to-service endpoints
                        .requestMatchers("/internal/api/v1/**").permitAll()
                        // User-facing endpoints
                        .requestMatchers("/api/v1/**").permitAll()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(
                        new InternalServiceAuthFilter(),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Internal service authentication filter.
     *
     * For /internal/** paths: validates X-Internal-Service header
     * against the allowed services list.
     *
     * For /api/v1/** paths: validates X-User-Id header presence
     * (actual JWT validation is done by the API Gateway).
     */
    static class InternalServiceAuthFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {

            String path = request.getRequestURI();

            // Internal endpoints: require X-Internal-Service header
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

            // User endpoints: X-User-Id is checked by controllers, not here
            // (API Gateway sets this after JWT validation)

            filterChain.doFilter(request, response);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI();
            // Don't filter actuator health checks
            return path.startsWith("/actuator/health");
        }
    }
}