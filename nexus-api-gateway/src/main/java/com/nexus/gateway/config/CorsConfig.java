package com.nexus.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS Configuration — Cross-Origin Resource Sharing policy.
 *
 * Configures allowed origins for the Nexus frontend clients:
 *   - localhost:3000  (React dev server)
 *   - localhost:5173  (Vite dev server)
 *
 * In production: replace with actual domain names.
 *
 * Note: CORS is also configured via application.yml globalcors.
 * This bean provides programmatic control and overrides YAML when present.
 *
 * 
 * askljlksalkshlakshs
 * +asmklsajiolksajnm
 */
@Configuration
public class CorsConfig {

    @Value("${nexus.gateway.security.cors.allowed-origins:" +
            "http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Parse comma-separated origins from config
        List<String> origins = Arrays.asList(
                allowedOrigins.split(","));
        config.setAllowedOrigins(origins.stream()
                .map(String::trim)
                .toList());

        // HTTP methods allowed cross-origin
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE",
                "PATCH", "OPTIONS"));

        // Headers the browser is allowed to send
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Request-Id",
                "X-Idempotency-Key",
                "Accept",
                "Accept-Language",
                "Cache-Control"));

        // Headers the browser JavaScript can read from responses
        config.setExposedHeaders(List.of(
                "X-Request-Id",
                "X-Gateway-Version",
                "X-RateLimit-Remaining",
                "X-RateLimit-Reset",
                "Retry-After"));

        // Allow credentials (Authorization header / cookies)
        config.setAllowCredentials(true);

        // Cache preflight response for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        // Apply to all paths
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}