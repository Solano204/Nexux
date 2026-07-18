package com.nexus.kyc.config;

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
 * Mixed service like identity-service — KycController (user-facing) uses
 * X-User-Id, InternalKycController (Docker network only, not routed
 * through the gateway) has no scheme of its own to document.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiKycServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8091").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/kyc").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus AI KYC Service")
                        .version("1.0.0")
                        .description("""
                                Identity verification pipeline — AWS Rekognition + LLM comparison \
                                against submitted documents. KycController (/api/v1/kyc) is \
                                user-facing; InternalKycController (/internal/v1/kyc) is service-to-service \
                                only, not routed through nexus-api-gateway at all.

                                POST /verify kicks off an async pipeline (same pattern as \
                                identity-service's /users/me/kyc/initiate) — the response here is \
                                actually synchronous today (waits for the pipeline), unlike identity's \
                                202-then-poll pattern; check getStatus if you need to confirm this \
                                hasn't changed.""")
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
                                                "Only enforced on KycController's endpoints. " +
                                                "For local testing, set it to any UUID.")));
    }
}
