package com.nexus.tracing.kafka;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;

/**
 * Manual Kafka trace propagation - explicit counterpart to Spring Kafka's
 * observation-enabled auto-instrumentation (see nexus-platform-config/
 * application-prod.yml for why that flag alone isn't relied on here: it's
 * only consistently wired for services using Spring Boot's auto-configured
 * KafkaTemplate/listener factory, not the 6 services with a hand-rolled
 * KafkaConfig).
 *
 * Uses the Tracer/Propagator beans Spring Boot already auto-configures from
 * management.tracing.* - no new dependencies, no new beans declared here.
 */
public final class KafkaTracePropagation {

    private static final Propagator.Setter<Headers> HEADER_SETTER = (carrier, key, value) -> {
        if (carrier == null) return;
        carrier.remove(key);
        carrier.add(key, value.getBytes(StandardCharsets.UTF_8));
    };

    private static final Propagator.Getter<Headers> HEADER_GETTER = (carrier, key) -> {
        if (carrier == null) return null;
        Header header = carrier.lastHeader(key);
        // header.value() is null (not just header itself) whenever the
        // source column was SQL NULL and got promoted verbatim by
        // Debezium's outbox EventRouter SMT (table.fields.additional.
        // placement copies the column's actual value, NULL included, not
        // "header absent" - see OutboxEntry.attachTraceContext, which is
        // a documented no-op when there's no active span at write time).
        if (header == null || header.value() == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    };

    private KafkaTracePropagation() {
    }

    /**
     * Writes the current span's context onto the outgoing record's headers.
     * No-op if there is no current span (record goes out untraced rather
     * than throwing).
     */
    public static void injectTraceHeaders(Tracer tracer, Propagator propagator,
                                          ProducerRecord<?, ?> record) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null) return;
        TraceContext context = currentSpan.context();
        propagator.inject(context, record.headers(), HEADER_SETTER);
    }

    /**
     * Rebuilds a span from incoming Kafka headers and starts it as a child
     * of whatever context those headers carry. If no valid trace context is
     * present, the underlying propagator starts a new root span rather than
     * failing, so this never returns null.
     *
     * Caller is responsible for opening a Tracer.SpanInScope around the
     * business logic and ending the span in a finally block:
     *
     *   Span span = KafkaTracePropagation.extractAndStartSpan(tracer, propagator, headers, "topic receive");
     *   try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
     *       // existing method body, unchanged
     *   } finally {
     *       span.end();
     *   }
     */
    public static Span extractAndStartSpan(Tracer tracer, Propagator propagator,
                                           Headers headers, String spanName) {
        Span.Builder builder = propagator.extract(headers, HEADER_GETTER);
        return builder.name(spanName).kind(Span.Kind.CONSUMER).start();
    }

    /**
     * Same as extractAndStartSpan(Headers), but also tags kafka.partition,
     * kafka.offset, kafka.topic and kafka.consumer.group on the started span.
     * Without these, proving "duplicate consumption" or "lost message" from
     * Zipkin tags alone isn't possible - you'd have to infer it from receive
     * timestamps, which is exactly what made Issue 1 (duplicate saga.replies
     * processing) hard to diagnose before the retry-storm root cause was
     * found by reading code, not traces.
     *
     * groupId is passed explicitly rather than read from the record/consumer
     * because @KafkaListener's groupId is only known at the annotation site,
     * not derivable from a ConsumerRecord.
     */
    public static Span extractAndStartSpan(Tracer tracer, Propagator propagator,
                                           ConsumerRecord<?, ?> record, String groupId,
                                           String spanName) {
        Span span = extractAndStartSpan(tracer, propagator, record.headers(), spanName);
        span.tag("kafka.topic", record.topic());
        span.tag("kafka.partition", String.valueOf(record.partition()));
        span.tag("kafka.offset", String.valueOf(record.offset()));
        span.tag("kafka.consumer.group", groupId);
        return span;
    }
}
