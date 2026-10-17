package com.paymesh.gateway.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/** Health probes (every few seconds, forever) would bury the real request traces. */
@Configuration
public class ObservationFilters {

    @Bean
    public ObservationPredicate noProbeTraces() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getPath().value().startsWith("/actuator"));
    }
}
