package com.paymesh.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Keeps a payment in one trace across the outbox. The outbox row stores the W3C traceparent of the request that
 * wrote it; the relay, which sends later from its own thread, sends inside a span restored from it, so the Kafka
 * send (and everything downstream) joins the original trace. Without a tracer (tests, tracing off) it does nothing.
 */
class OutboxTracing {

    private static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    OutboxTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current trace as a traceparent header value, or null outside any trace. */
    String currentTraceparent() {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null || p == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        p.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Runs the send as part of the stored trace, in a span named after the topic; plainly if there is none. */
    <T> T inTrace(String traceparent, String topic, Supplier<T> send) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (traceparent == null || t == null || p == null) {
            return send.get();
        }
        Span span = p.extract(Map.of(TRACEPARENT, traceparent), Map::get).name("outbox relay " + topic).start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return send.get();
        } finally {
            span.end();
        }
    }
}
