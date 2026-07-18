package com.nexus.saga.config;

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

/** Same pattern as fraud-service/risk-scoring-service's OpenApiConfig. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sagaOrchestratorOpenAPI() {
        final String securitySchemeName = "X-Internal-Service";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8095").description("Local (Docker network only in prod)")
                ))
                .info(new Info()
                        .title("Nexus Saga Orchestrator")
                        .version("1.0.0")
                        .description("""
                                Coordinates the transfer saga (fraud check → balance reservation → \
                                ledger posting → notification) and the onboarding saga (KYC). \
                                Production coordination is 100% Kafka-driven (see \
                                CHANGES-BESTPRACTICES/01_SAGA_PATTERN_CHANGES.md) — every endpoint \
                                here is read-only introspection (saga state, stats, stuck-saga \
                                detection), not part of the saga execution path itself.

                                No confirmed real HTTP caller found for these endpoints as of the last \
                                audit — the X-Internal-Service allow-list is a placeholder based on \
                                architectural plausibility. See \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md.""")
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
