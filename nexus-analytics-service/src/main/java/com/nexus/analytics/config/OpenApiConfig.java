package com.nexus.analytics.config;

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
    public OpenAPI analyticsServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8092").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/analytics").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Analytics Service")
                        .version("1.0.0")
                        .description("""
                                Spending analytics, trends, top merchants, and AI-generated insights, \
                                built by a Kafka Streams pipeline (see InternalAnalyticsController for \
                                the internal state-store query endpoints other services read from — \
                                not routed through the gateway).

                                Known contract quirk, not fixed as part of this documentation pass: \
                                accountId in the URL path is captured but never used by \
                                AnalyticsController/InsightsController's query methods — every result \
                                is scoped by the caller's userId only, aggregated across all their \
                                accounts. Any accountId value returns the same data. See \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md, Sección 10, for \
                                whether this is intended design or a bug — undetermined as of that audit.""")
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
