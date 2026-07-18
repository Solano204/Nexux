package com.nexus.risk.config;

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
 * /internal/v1/risk had no application-layer identity check at all —
 * GET /profiles/{userId} returned a full risk profile, and
 * POST /batch/trigger kicked off the OpenAI-backed nightly batch, to
 * anyone who could reach the service, network isolation being the only
 * boundary. Same X-Internal-Service allow-list filter as
 * nexus-account-service/nexus-fraud-service's SecurityConfig.
 *
 * No confirmed real caller found in code (risk data flows out via Redis,
 * written by this service, read by fraud-service — not via this HTTP
 * API) — the allow-list below is a placeholder based on architectural
 * plausibility, not a verified list. Adjust before relying on it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Set<String> ALLOWED_INTERNAL_SERVICES = Set.of(
            "nexus-fraud-service",
            "nexus-saga-orchestrator",
            "nexus-api-gateway"
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
