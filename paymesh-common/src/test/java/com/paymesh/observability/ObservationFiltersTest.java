package com.paymesh.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
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
}
