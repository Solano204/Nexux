package com.nexus.risk.config;

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
 * 100% internal, same pattern as fraud-service's OpenApiConfig —
 * X-Internal-Service is the real security scheme, checked by
 * SecurityConfig.InternalServiceAuthFilter.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI riskScoringServiceOpenAPI() {
        final String securitySchemeName = "X-Internal-Service";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8094").description("Local (Docker network only in prod)")
                ))
                .info(new Info()
                        .title("Nexus Risk Scoring Service")
                        .version("1.0.0")
                        .description("""
                                Internal-only — every endpoint is under /internal/v1/risk. No confirmed \
                                real HTTP caller as of the last audit: fraud-service reads risk data \
                                via Redis (written by this service's nightly batch), not this API — \
                                these endpoints look like admin/ops tooling for manual profile \
                                inspection and batch triggering, not a production integration point. \
                                See CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md for the full \
                                reasoning, including why the X-Internal-Service allow-list here is a \
                                placeholder, not a verified list.""")
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
