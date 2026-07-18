package com.nexus.identity.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Unlike account-service/transaction-service, this service is a mix of
 * genuinely public endpoints (AuthController — register/login/etc, no
 * auth possible by definition) and authenticated ones (UserController,
 * KycController). No global addSecurityItem here — the X-User-Id scheme
 * is only declared in components and applied per-operation via
 * @SecurityRequirement on UserController/KycController/InternalController
 * methods, so Swagger UI doesn't show a misleading lock icon on
 * /auth/register.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI identityServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8083").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/auth").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Identity Service")
                        .version("1.0.0")
                        .description("""
                                Authentication, user profiles, and KYC initiation. This is the JWT \
                                issuer for the whole platform — nexus-api-gateway is the only other \
                                service that ever validates a token, everything else trusts the \
                                X-User-Id header the gateway sets after validation.

                                AuthController's endpoints (register/login/refresh-token/logout/jwks) \
                                are genuinely public — no auth required, that's the point. Every other \
                                controller here requires X-User-Id like the rest of the platform.

                                Errors follow RFC 9457 (Problem Details) — see \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md, Sección 3.""")
                        .contact(new Contact()
                                .name("NEXUS Platform")))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("User ID set by nexus-api-gateway after JWT validation. " +
                                                "Not required on AuthController's public endpoints. " +
                                                "For local testing directly against this service, set it to any UUID.")));
    }
}
