package com.paymesh.gateway.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationFiltersTest {

    private final ObservationPredicate predicate = new ObservationFilters().noProbeTraces();

    private static ServerRequestObservationContext request(String path) {
        return new ServerRequestObservationContext(MockServerHttpRequest.get(path).build(), new MockServerHttpResponse(), Map.of());
    }

    @Test
    void healthProbesAreNotObserved() {
        assertThat(predicate.test("http.server.requests", request("/actuator/health"))).isFalse();
    }

    @Test
    void apiRequestsAreObserved() {
        assertThat(predicate.test("http.server.requests", request("/api/wallets/me"))).isTrue();
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
