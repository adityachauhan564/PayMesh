package com.paymesh.gateway.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Health probes (every few seconds, forever) would bury the real request traces. Spring Security's observations of a
 * dropped probe request have no real parent (none, or the no-op one) and would start root traces, so they go too (null context = the
 * name-only pre-check).
 */
@Configuration
public class ObservationFilters {

    @Bean
    public ObservationPredicate noProbeTraces() {
        return (name, context) -> {
            if (name.startsWith("spring.security.") && context != null
                    && (!(context.getParentObservation() instanceof Observation parent) || parent.isNoop())) {
                return false;
            }
            return !(context instanceof ServerRequestObservationContext request
                    && request.getCarrier().getPath().value().startsWith("/actuator"));
        };
    }
}
