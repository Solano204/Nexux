package com.nexus.ledger.config;

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
 * OpenApiConfig for LedgerController (user-facing). InternalLedgerController
 * has no auth scheme of its own to document — protected only by the
 * gateway's RemoteAddr predicate, same caveat as identity-service's
 * getIdentitySummary.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerServiceOpenAPI() {
        final String securitySchemeName = "X-User-Id";

        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8088").description("Local (direct, bypasses gateway)"),
                        new Server().url("http://localhost:8080/api/v1/ledger").description("Via nexus-api-gateway (production path)")
                ))
                .info(new Info()
                        .title("Nexus Ledger Service")
                        .version("1.0.0")
                        .description("""
                                Double-entry ledger — the source of truth for account balances. \
                                LedgerController (/api/v1/ledger) is user-facing; \
                                InternalLedgerController (/internal/v1/ledger) is admin/ops tooling \
                                (manual postings, reversals, reconciliation) called by other services, \
                                not end users.

                                Ownership is verified against chart_of_accounts.user_id, replicated \
                                locally via Kafka from account-service's accounts.created event (CDC \
                                outbox) — not a synchronous call to account-service. See \
                                CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md, sexta ronda, for \
                                why that matters (a brief eventual-consistency window right after \
                                account creation).

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
                                                "Only enforced on LedgerController's endpoints, not InternalLedgerController's. " +
                                                "For local testing, set it to any UUID.")));
    }
}
