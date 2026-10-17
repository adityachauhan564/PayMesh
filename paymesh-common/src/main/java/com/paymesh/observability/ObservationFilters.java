package com.paymesh.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about requests and messages. Scheduled pollers (the outbox relay runs every 250 ms in every service)
 * and Kubernetes health probes would otherwise outnumber real traces by orders of magnitude. The relay's actual
 * sends still show up: they join the trace of the request that wrote the event (see OutboxTracing).
 * Spring Security observes each request too; for a dropped probe request those observations have no parent and
 * would start root traces of their own, so ones without a real parent are dropped. The context is null on the name-only pre-check.
 */
@Configuration
public class ObservationFilters {

    @Bean
    public ObservationPredicate noPollerOrProbeTraces() {
        return (name, context) -> {
            if ("tasks.scheduled.execution".equals(name)) {
                return false;
            }
            if (name.startsWith("spring.security.") && context != null
                    && (!(context.getParentObservation() instanceof Observation parent) || parent.isNoop())) {
                return false;
            }
            return !(context instanceof ServerRequestObservationContext request
                    && request.getCarrier().getRequestURI().startsWith("/actuator"));
        };
    }
}
