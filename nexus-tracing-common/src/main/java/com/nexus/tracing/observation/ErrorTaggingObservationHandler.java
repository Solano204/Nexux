package com.nexus.tracing.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

/**
 * Platform-wide error tagging - not a Spring bean itself (this jar declares
 * none, see pom.xml), instantiated with `new` from each service's
 * ObservabilityConfig and registered via ObservationRegistryCustomizer.
 *
 * Brave's own TracingObservationHandler already marks a span red (error=true
 * + exception tag) whenever Observation.error(ex) is called - that part is
 * automatic today for HTTP requests (Spring's ServerHttpObservationFilter
 * calls it on uncaught 5xx) and for @Observed business methods (ObservedAspect
 * calls it when the underlying method throws). What's NOT consistent today is
 * the error.type tag itself: some Kafka consumer catch blocks add it by hand
 * (e.g. FraudCommandConsumer's Observation.createNotStarted("kafka.message.
 * processed", ...) + obs.error(e)), most don't. This handler makes it
 * automatic and consistent for every Observation in the app - HTTP, @Observed,
 * or a manually created one - anywhere .error(ex) is called.
 */
public class ErrorTaggingObservationHandler implements ObservationHandler<Observation.Context> {

    @Override
    public void onError(Observation.Context context) {
        Throwable throwable = context.getError();
        if (throwable != null) {
            context.addLowCardinalityKeyValue(
                    KeyValue.of("error.type", throwable.getClass().getSimpleName()));
        }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }
}
