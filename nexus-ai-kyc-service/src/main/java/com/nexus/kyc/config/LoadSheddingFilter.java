package com.nexus.kyc.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load Shedding Filter (resiliencia guide, Fase 6) — see
 * nexus-transaction-service's copy for the full design rationale.
 * Lower threshold here (Nivel 3, Fase 5) — this service should shed before
 * Nivel 1/2 services feel any pressure. Separate from (and in front of) the
 * ThreadPoolBulkhead added in Fase 4 — this filter bounds inbound HTTP
 * concurrency at the servlet layer; the bulkhead bounds concurrent calls
 * INTO the OpenAI-dependent verify() pipeline specifically.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoadSheddingFilter implements Filter {

    @Value("${nexus.load-shedding.max-in-flight:40}")
    private int maxInFlight;

    @Value("${nexus.load-shedding.enabled:true}")
    private boolean enabled;

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final Counter rejectionCounter;

    public LoadSheddingFilter(MeterRegistry meterRegistry) {
        this.rejectionCounter = Counter.builder("loadshedding.rejections.total")
                .description("Requests rejected by the load shedding filter")
                .register(meterRegistry);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        if (!enabled || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        int current = inFlight.incrementAndGet();
        try {
            if (current > maxInFlight) {
                rejectionCounter.increment();
                log.warn("Load shedding: rejecting request, inFlight={} max={}",
                        current, maxInFlight);

                httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                httpResponse.setContentType("application/json");
                httpResponse.setHeader("Retry-After", "2");
                httpResponse.getWriter().write("""
                        {"error":"SERVICE_OVERLOADED","message":"Service is at capacity, please retry shortly.","retryAfter":2}""");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
