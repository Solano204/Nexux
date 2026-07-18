package com.nexus.audit.query.config;

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
 * Two headers, not one — X-User-Id identifies the caller (for the audit
 * trail of who ran a compliance query), X-User-Roles is the real
 * authorization check (COMPLIANCE_OFFICER or ADMIN required on every
 * endpoint here). Both are set by nexus-api-gateway after JWT
 * validation — see JwtAuthenticationFilter's roles claim handling.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI auditQueryOpenAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8097").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/audit").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Audit Query Service")
                        .version("1.0.0")
                        .description("""
                                Compliance and audit investigation — every endpoint requires the caller \
                                to have COMPLIANCE_OFFICER or ADMIN in X-User-Roles, not just a valid \
                                X-User-Id. This was a real gap closed during \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md: the role check was \
                                documented in this service's own SecurityConfig Javadoc but never \
                                actually wired to any controller before that audit.""")
                        .contact(new Contact()
                                .name("NEXUS Platform")))
                .addSecurityItem(new SecurityRequirement().addList("X-User-Id").addList("X-User-Roles"))
                .components(new Components()
                        .addSecuritySchemes("X-User-Id",
                                new SecurityScheme()
                                        .name("X-User-Id")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("Caller's user ID, set by nexus-api-gateway after JWT validation."))
                        .addSecuritySchemes("X-User-Roles",
                                new SecurityScheme()
                                        .name("X-User-Roles")
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .description("Comma-separated roles from the JWT's roles claim. Must contain " +
                                                "COMPLIANCE_OFFICER or ADMIN. For local testing, set it to that value directly.")));
    }
}
