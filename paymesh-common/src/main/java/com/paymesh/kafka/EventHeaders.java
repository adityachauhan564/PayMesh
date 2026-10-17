package com.paymesh.kafka;

/** Kafka headers on every PayMesh event (set by OutboxRelay). */
public final class EventHeaders {

    /** Unique per event; consumers that can't dedupe on business keys dedupe on this. */
    public static final String EVENT_ID = "eventId";

    /** The request's X-Correlation-Id, so one payment can be followed through every service's logs. */
    public static final String CORRELATION_ID = "correlationId";

    private EventHeaders() {
    }
}
