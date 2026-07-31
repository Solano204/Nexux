package com.nexus.tracing.http;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

/**
 * Stamps outbound service-to-service HTTP calls with the two headers every
 * NEXUS service's SecurityConfig.InternalServiceAuthFilter checks:
 * X-Internal-Service (service identity, checked against an allow-list on
 * /internal/** routes) and X-User-Id (the end-user identity the API
 * Gateway already validated the JWT for, forwarded as-is - no service
 * downstream of the gateway re-validates the JWT itself, see
 * CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md Fase 3).
 *
 * Same "context that travels with the request" principle as
 * KafkaTracePropagation (com.nexus.tracing.kafka) - that one carries
 * traceId across the Kafka boundary, this one carries identity across the
 * HTTP boundary. Both live here for the same reason: every service should
 * get this for free from a shared interceptor instead of reimplementing
 * header-forwarding per REST client.
 *
 * Works with both RestTemplate (RestTemplateBuilder.additionalInterceptors)
 * and RestClient (RestClient.Builder.requestInterceptor) - both accept
 * plain ClientHttpRequestInterceptor.
 *
 * Silently forwards nothing if there is no current HTTP request (e.g. a
 * background job or Kafka consumer making an outbound call) - the
 * X-Internal-Service header still gets set either way, since that
 * identifies THIS service regardless of what triggered the call.
 */
public class InternalServiceHeaderInterceptor implements ClientHttpRequestInterceptor {

    private final String serviceName;

    public InternalServiceHeaderInterceptor(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set("X-Internal-Service", serviceName);

        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            String userId = servletAttrs.getRequest().getHeader("X-User-Id");
            if (userId != null) {
                request.getHeaders().set("X-User-Id", userId);
            }
        }

        return execution.execute(request, body);
    }
}
