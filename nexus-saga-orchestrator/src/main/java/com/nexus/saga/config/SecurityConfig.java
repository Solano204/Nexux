package com.nexus.saga.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * /internal/v1/sagas had no application-layer identity check — anyone
 * reaching the service could read any transfer/onboarding saga state
 * (GET /onboarding/{userId} exposes KYC step progress) with network
 * isolation as the only boundary. Same X-Internal-Service allow-list
 * filter as nexus-account-service/nexus-fraud-service/nexus-risk-scoring-service.
 *
 * No confirmed real caller found in code — saga-orchestrator's real
 * production integrations are Kafka-driven (see
 * CHANGES-BESTPRACTICES/01_SAGA_PATTERN_CHANGES.md), these REST
 * endpoints look like read-only ops/admin introspection. Allow-list
 * below is a placeholder based on architectural plausibility, not a
 * verified list.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Set<String> ALLOWED_INTERNAL_SERVICES = Set.of(
            "nexus-api-gateway",
            "nexus-audit-query-jvm",
            "nexus-ai-assistant-service"
    );

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/internal/**", "/actuator/**").permitAll()
                .requestMatchers(
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(
                    new InternalServiceAuthFilter(),
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

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
