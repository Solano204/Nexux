package com.nexus.account.config;

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
 * X-User-Id is documented here, not Bearer JWT — this is what
 * account-service itself actually validates. Production traffic gets
 * here via nexus-api-gateway, which validates the JWT and sets this
 * header; a Bearer token sent directly to this service's own Swagger UI
 * (bypassing the gateway) would not work, only X-User-Id does.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI accountServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8085").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/accounts").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Account Service")
                        .version("1.0.0")
                        .description("""
                                Account management — balances, events, AI-generated analytics and \
                                financial advisor endpoints. Part of the NEXUS fintech platform.

                                Authentication: production traffic goes through nexus-api-gateway, \
                                which validates the caller's JWT and forwards the request here with \
                                an X-User-Id header already set. This service trusts that header \
                                without re-validating the JWT itself — see \
                                CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md for why.

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
