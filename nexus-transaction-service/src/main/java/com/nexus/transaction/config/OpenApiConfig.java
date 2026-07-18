package com.nexus.transaction.config;

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
 * Same X-User-Id documentation approach as account-service's
 * OpenApiConfig — see that class's Javadoc for the full reasoning.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8086").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/transactions").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Transaction Service")
                        .version("1.0.0")
                        .description("""
                                Money movement — transfers, payments, transaction history and search. \
                                Every transfer/payment kicks off a saga (see nexus-saga-orchestrator) \
                                that coordinates fraud checks, balance reservation, and ledger posting.

                                Authentication: production traffic goes through nexus-api-gateway, \
                                which validates the caller's JWT and forwards the request here with \
                                an X-User-Id header already set. This service trusts that header \
                                without re-validating the JWT itself — see \
                                CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md for why.

                                Idempotency: POST /transfer and /payment require an idempotencyKey \
                                field in the body (8-64 chars) — retries with the same key return the \
                                original response instead of creating a duplicate transaction.

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
