package com.nexus.gateway.filter;

import com.nexus.gateway.featureflag.FeatureFlagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Feature Flag Filter — Custom GatewayFilterFactory (resiliencia guide,
 * Fase 7, punto 2-3).
 *
 * Applied via:
 *   filters:
 *     - name: FeatureFlag
 *       args:
 *         feature: ai-assistant
 * in a route's application.yml definition. When the named flag is
 * disabled, returns the same kind of graceful-degradation response
 * FallbackController gives for a tripped circuit breaker — but triggered
 * by an explicit, observable, deliberately-set flag instead of purely by
 * per-call failure detection. Reuses the exact JSON shape
 * FallbackController's /fallback/ai-assistant already returns, so callers
 * see one consistent contract regardless of which mechanism degraded
 * ai-assistant-service.
 *
 * Class name MUST contain the exact substring "GatewayFilterFactory" —
 * Spring Cloud Gateway's NameUtils.normalizeFilterFactoryName() derives
 * the YAML-referenceable filter name ("FeatureFlag" above) by string-
 * replacing that substring out of the simple class name, not by suffix
 * matching. The original name (FeatureFlagFilterFactory, missing
 * "Gateway") never matched, so this filter silently registered under its
 * full class name instead of "FeatureFlag" — confirmed live: application
 * startup failed with "Unable to find GatewayFilterFactory with name
 * FeatureFlag".
 */
@Slf4j
@Component
public class FeatureFlagGatewayFilterFactory
        extends AbstractGatewayFilterFactory<FeatureFlagGatewayFilterFactory.Config> {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagGatewayFilterFactory(FeatureFlagService featureFlagService) {
        super(Config.class);
        this.featureFlagService = featureFlagService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) ->
                featureFlagService.isEnabled(config.getFeature())
                        .flatMap(enabled -> enabled
                                ? chain.filter(exchange)
                                : degraded(exchange, config.getFeature()));
    }

    private Mono<Void> degraded(ServerWebExchange exchange, String feature) {
        log.info("Feature '{}' is disabled — serving degraded response", feature);

        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                  "error": "AI_ASSISTANT_UNAVAILABLE",
                  "message": "AI assistant is temporarily unavailable. \
                You can still use all standard banking features at /api/v1/",
                  "alternativeEndpoints": {
                    "accounts": "/api/v1/accounts",
                    "transactions": "/api/v1/transactions",
                    "ledger": "/api/v1/ledger"
                  }
                }
                """;

        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    public static class Config {
        private String feature;

        public String getFeature() { return feature; }
        public void setFeature(String feature) { this.feature = feature; }
    }
}
