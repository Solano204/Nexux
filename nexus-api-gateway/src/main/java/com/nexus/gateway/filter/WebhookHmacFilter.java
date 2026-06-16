package com.nexus.gateway.filter;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Webhook HMAC Filter — Validates HMAC signatures on payment network webhooks.
 *
 * Used for: /api/v1/webhooks/** routes (payment network callbacks from AWS)
 * Authentication: HMAC-SHA256 signature in X-Signature header
 *
 * Security: Constant-time comparison prevents timing attacks.
 * Pattern: Strategy Pattern — HMAC validation as interchangeable auth strategy
 */
@Slf4j
@Component
public class WebhookHmacFilter
        extends AbstractGatewayFilterFactory<WebhookHmacFilter.Config> {

    @Value("${nexus.gateway.webhook.hmac-secret:}")
    private String hmacSecret;

    private final ObservationRegistry observationRegistry;

    public WebhookHmacFilter(ObservationRegistry observationRegistry) {
        super(Config.class);
        this.observationRegistry = observationRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String providedSignature = exchange.getRequest()
                    .getHeaders().getFirst("X-Signature");

            if (providedSignature == null || providedSignature.isBlank()) {
                return writeUnauthorized(exchange, "MISSING_WEBHOOK_SIGNATURE");
            }

            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .flatMap(dataBuffer -> {
                        byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bodyBytes);
                        DataBufferUtils.release(dataBuffer);

                        try {
                            String computedSignature = computeHmac(bodyBytes);

                            // Constant-time comparison — prevents timing attacks
                            boolean valid = MessageDigest.isEqual(
                                    computedSignature.getBytes(StandardCharsets.UTF_8),
                                    providedSignature.getBytes(StandardCharsets.UTF_8)
                            );

                            if (!valid) {
                                log.warn("Webhook HMAC validation failed: " +
                                                "path={} remoteAddr={}",
                                        exchange.getRequest().getPath(),
                                        exchange.getRequest().getRemoteAddress()
                                );
                                return writeUnauthorized(exchange,
                                        "INVALID_WEBHOOK_SIGNATURE");
                            }

                            // Rebuild request with body (already consumed)
                            // Create a DataBuffer from the body bytes
                            DataBuffer bodyDataBuffer = exchange.getResponse()
                                    .bufferFactory()
                                    .wrap(bodyBytes);

                            // Use ServerHttpRequestDecorator to override the body
                            ServerHttpRequestDecorator decoratedRequest =
                                    new ServerHttpRequestDecorator(exchange.getRequest()) {
                                        @Override
                                        public Flux<DataBuffer> getBody() {
                                            return Flux.just(bodyDataBuffer);
                                        }
                                    };

                            // Mutate exchange with the decorated request
                            ServerWebExchange mutatedExchange = exchange.mutate()
                                    .request(decoratedRequest)
                                    .build();

                            return chain.filter(mutatedExchange);

                        } catch (Exception e) {
                            log.error("HMAC computation failed: {}", e.getMessage());
                            return writeUnauthorized(exchange, "SIGNATURE_VERIFICATION_ERROR");
                        }
                    });
        };
    }

    private String computeHmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
                hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] signatureBytes = mac.doFinal(body);
        return "sha256=" + HexFormat.of().formatHex(signatureBytes);
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange,
                                         String errorCode) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders()
                .setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"error\": \"%s\", \"message\": \"Webhook signature validation failed\"}",
                errorCode);

        var buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public String name() {
        return "WebhookHmac";
    }

    public static class Config {}
}