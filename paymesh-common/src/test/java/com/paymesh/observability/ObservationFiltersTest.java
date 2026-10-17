package com.paymesh.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Pollers and probes would bury the real traces: the outbox relay alone runs 4 times a second per service. */
class ObservationFiltersTest {

    private final ObservationPredicate predicate = new ObservationFilters().noPollerOrProbeTraces();

    private static ServerRequestObservationContext request(String uri) {
        HttpServletResponse response = new MockHttpServletResponse();
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri), response);
    }

    @Test
    void scheduledTasksAreNotObserved() {
        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isFalse();
    }

    @Test
    void healthProbesAreNotObserved() {
        assertThat(predicate.test("http.server.requests", request("/actuator/health/liveness"))).isFalse();
    }

    @Test
    void apiRequestsAndKafkaAreObserved() {
        assertThat(predicate.test("http.server.requests", request("/api/transactions"))).isTrue();
        assertThat(predicate.test("spring.kafka.listener", new Observation.Context())).isTrue();
    }

    /** A probe's request observation is dropped, so Spring Security's observations under it would start root traces. */
    @Test
    void dropsParentlessSecurityObservations() {
        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isFalse();
        assertThat(predicate.test("spring.security.filterchains", null)).isTrue(); // the name-only pre-check
    }

    /** Spring Security takes the request's observation as parent: for a dropped probe request that is a no-op one. */
    @Test
    void dropsSecurityObservationsUnderADroppedRequest() {
        Observation.Context context = new Observation.Context();
        context.setParentObservation(Observation.NOOP);
        assertThat(predicate.test("spring.security.filterchains", context)).isFalse();
    }

    @Test
    void keepsSecurityObservationsInsideARequest() {
        ObservationRegistry registry = ObservationRegistry.create(); // a registry without handlers only makes no-ops
        registry.observationConfig().observationHandler(context -> true);
        Observation.Context context = new Observation.Context();
        context.setParentObservation(Observation.start("http.server.requests", registry));
        assertThat(predicate.test("spring.security.filterchains", context)).isTrue();
    }
}
