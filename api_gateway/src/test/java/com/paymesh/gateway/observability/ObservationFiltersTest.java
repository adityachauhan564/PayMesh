package com.paymesh.gateway.observability;

import io.micrometer.observation.ObservationPredicate;
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
}
