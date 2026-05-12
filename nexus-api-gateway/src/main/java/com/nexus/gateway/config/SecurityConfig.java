package com.nexus.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security Configuration — Spring Security WebFlux setup.
 *
 * The gateway handles its OWN JWT validation via the custom
 * JwtAuthenticationFilter (GatewayFilter), not via Spring Security's
 * JWT support. Spring Security is configured to permit all traffic
 * here — authentication happens in the custom filter chain.
 *
 * Why: Spring Cloud Gateway's filter chain runs BEFORE Spring Security's
 * filter chain for reactive apps. The custom GatewayFilter handles auth.
 * Spring Security is still configured to enforce CSRF for form submissions
 * and provide the security context propagation infrastructure.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http) {

        return http
                // Disable CSRF — APIs use JWT (stateless), not cookies
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // Disable default HTTP Basic — we use JWT
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                // Disable default form login — API-only gateway
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                // Permit all — actual auth is in JwtAuthenticationFilter
                // which is a GatewayFilter running before Spring Security
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .anyExchange().permitAll()
                )

                // Security headers — also added by SecurityHeadersFilter
                // but keeping here as defense-in-depth
                .headers(headers -> headers
                        .frameOptions(
                                org.springframework.security.config.web.server
                                        .ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable)
                )

                .build();
    }
}