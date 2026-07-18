package com.nexus.fraud.config;

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

/**
 * 100% internal service — no user-facing endpoints, no X-User-Id
 * scheme. The real security scheme here is X-Internal-Service, checked
 * by SecurityConfig.InternalServiceAuthFilter against an allow-list.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fraudServiceOpenAPI() {
        final String securitySchemeName = "X-Internal-Service";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8087").description("Local (Docker network only in prod)")
                ))
                .info(new Info()
                        .title("Nexus Fraud Service")
                        .version("1.0.0")
                        .description("""
                                Internal-only — every endpoint is under /internal/v1/fraud, called by \
                                other NEXUS services, never routed here from nexus-api-gateway's \
                                public surface. Production fraud analysis mostly runs via Kafka \
                                (FraudCommandConsumer), not these HTTP endpoints — POST /analyze exists \
                                for direct/emergency/testing use, see its own description.

                                Errors follow RFC 9457 (Problem Details) for most endpoints — see \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md, Sección 3, for \
                                the one gap (this service doesn't have a centralized \
                                GlobalExceptionHandler yet, some error paths fall to Spring's default).""")
                        .contact(new Contact()
                                .name("NEXUS Platform")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("Caller's own service name, checked against an allow-list " +
                                                "(SecurityConfig.ALLOWED_INTERNAL_SERVICES). " +
                                                "For local testing, use any allow-listed value, e.g. nexus-api-gateway.")));
    }
}
