package com.nexus.tracing.sampling;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.util.List;

/**
 * Replaces CriticalPathHttpSampler, which never worked: it implemented
 * Brave's SamplerFunction<HttpRequest>, wired on the (incorrect) assumption
 * that Spring Boot's BraveAutoConfiguration builds an HttpTracing/
 * HttpServerHandler that consults it. Decompiling BraveAutoConfiguration
 * from spring-boot-actuator-autoconfigure-3.5.3.jar shows it only wires
 * Tracing/Tracer/Sampler/Propagation - no HttpTracing bean exists, and
 * nothing in that jar consumes SamplerFunction<HttpRequest>. Spring Boot
 * 3.x's HTTP server tracing goes entirely through ServerHttpObservationFilter
 * (the Observation API), which only ever asks the plain probability-based
 * brave.sampler.Sampler - so the old sampler was a dead bean, and every
 * /actuator/health poll kept generating a full trace regardless.
 *
 * ObservationPredicate is the correct hook: ObservationAutoConfiguration
 * auto-collects any bean of this type via ObjectProvider<ObservationPredicate>
 * (see ObservationAutoConfiguration.observationRegistryPostProcessor) and
 * registers it on the ObservationRegistry - a false return means the
 * Observation, and therefore any span, is never created at all.
 *
 * Only handles exclusion. Brave's classic Sampler interface has no request
 * path visibility, so there is no equivalent hook left to force a path to
 * 100% sampling below the global probability - moot today since
 * management.tracing.sampling.probability is 1.0 platform-wide, but would
 * need a different mechanism if that's ever lowered.
 */
public class ActuatorObservationPredicate implements ObservationPredicate {

    private final List<String> excludedPathPrefixes;

    public ActuatorObservationPredicate(List<String> excludedPathPrefixes) {
        this.excludedPathPrefixes = excludedPathPrefixes;
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext serverContext) {
            HttpServletRequest request = serverContext.getCarrier();
            if (request != null) {
                String uri = request.getRequestURI();
                for (String prefix : excludedPathPrefixes) {
                    if (uri.startsWith(prefix)) return false;
                }
            }
        }
        return true;
    }
}
