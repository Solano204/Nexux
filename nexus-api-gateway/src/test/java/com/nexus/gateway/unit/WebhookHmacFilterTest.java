package com.nexus.gateway.unit;

import com.nexus.gateway.filter.WebhookHmacFilter;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookHmacFilterTest {

    @Mock private GatewayFilterChain chain;

    private GatewayFilter gatewayFilter;
    private static final String SECRET = "test-webhook-secret";
    private static final String BODY = "{\"event\":\"payment.completed\"}";

    @BeforeEach
    void setUp() {
        WebhookHmacFilter filter = new WebhookHmacFilter(ObservationRegistry.NOOP);
        ReflectionTestUtils.setField(filter, "hmacSecret", SECRET);
        gatewayFilter = filter.apply(new WebhookHmacFilter.Config());
    }

    private String validSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsRequestWithMissingSignatureHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/webhooks/payment").build());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsBlankSignatureHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/webhooks/payment")
                        .header("X-Signature", "").build());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsRequestWithInvalidSignature() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/webhooks/payment")
                        .header("X-Signature", "sha256=deadbeef")
                        .body(BODY));

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsRequestWithValidSignatureAndForwardsToChain() throws Exception {
        String signature = validSignature(BODY);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/webhooks/payment")
                        .header("X-Signature", signature)
                        .body(BODY));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }
}
