package com.nexus.audit.write.config;

import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Quarkus's quarkus-opentelemetry extension auto-discovers any CDI bean of
 * type SpanExporter and adds it to the span processor pipeline - this is a
 * stable extension point, unlike guessing a quarkus.otel.exporter.zipkin.*
 * config property name (which doesn't exist in this Quarkus version; a
 * config-based attempt at this failed with "Unrecognized configuration key").
 * quarkus.otel.exporter.otlp.enabled=false (application.properties) stops
 * the default OTLP exporter from also being registered alongside this one.
 */
@ApplicationScoped
public class ZipkinExporterProducer {

    @Produces
    @ApplicationScoped
    public SpanExporter zipkinSpanExporter(
            @ConfigProperty(name = "nexus.zipkin.endpoint",
                    defaultValue = "http://localhost:9412/api/v2/spans")
            String zipkinEndpoint) {
        return ZipkinSpanExporter.builder()
                .setEndpoint(zipkinEndpoint)
                .build();
    }
}
