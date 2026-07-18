package com.nexus.assistant.config;

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
    public OpenAPI aiAssistantServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8090").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/ai").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus AI Assistant Service")
                        .version("1.0.0")
                        .description("""
                                General-purpose conversational financial assistant, plus multimodal \
                                document analysis (receipts/bills). Every endpoint streams via \
                                Server-Sent Events — consume with an SSE client, not a plain HTTP \
                                client expecting one JSON response.

                                conversationId/session keys are always prefixed with the caller's own \
                                userId server-side, even when a client-supplied sessionId is used — a \
                                guessed session ID from another user never resolves to their \
                                conversation, see CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md \
                                for the full reasoning.""")
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
