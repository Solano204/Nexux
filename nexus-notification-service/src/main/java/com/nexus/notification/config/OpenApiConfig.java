package com.nexus.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Same X-User-Id approach as account-service's OpenApiConfig. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI notificationServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8089").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/notifications").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Notification Service")
                        .version("1.0.0")
                        .description("""
                                In-app notifications and delivery preferences (email/SMS/push channel \
                                config). Actual dispatch is event-driven — this service reacts to Kafka \
                                events from other services and stores/serves the resulting notifications, \
                                it doesn't send anything synchronously from these endpoints.

                                FRAUD_ALERT is the one channel that cannot be disabled via \
                                PUT /preferences — enforced server-side, regulatory requirement, not \
                                just a UI restriction.

                                Errors follow RFC 9457 (Problem Details) — see \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md, Sección 3.""")
                        .contact(new Contact()
                                .name("NEXUS Platform")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("User ID set by nexus-api-gateway after JWT validation. " +
                                                "For local testing directly against this service, set it to any UUID.")));
    }
}
