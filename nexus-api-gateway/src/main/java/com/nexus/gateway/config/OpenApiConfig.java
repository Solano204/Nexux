package com.nexus.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * This spec documents the gateway's OWN endpoints only (fallback
 * responses, feature-flag admin) — not the ~90 routed endpoints it
 * proxies to other services, each of which has its own spec on its own
 * port. No security scheme declared here: FallbackController is fully
 * public by design, and FeatureFlagAdminController does its own IP-based
 * check (not header-based), not a scheme springdoc can represent
 * cleanly — see that controller's own Javadoc for the reasoning.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI apiGatewayOpenAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local")
                ))
                .info(new Info()
                        .title("Nexus API Gateway")
                        .version("1.0.0")
                        .description("""
                                Entry point for all external traffic — validates JWTs \
                                (JwtAuthenticationFilter), enriches requests with X-User-Id/X-User-Roles, \
                                rate-limits per user per route (Redis-backed), and routes to the ~13 \
                                downstream services. See each service's own /swagger-ui.html for its \
                                routed endpoints — this spec only covers what the gateway itself \
                                implements: circuit-breaker fallback responses and feature-flag admin.

                                See API-DOCUMENTATION/02_AUTHENTICATION.md for the real authentication \
                                flow through this gateway.""")
                        .contact(new Contact()
                                .name("NEXUS Platform")));
    }
}
