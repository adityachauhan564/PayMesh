package com.paymesh.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about requests and messages. Scheduled pollers (the outbox relay runs every 250 ms in every service)
 * and Kubernetes health probes would otherwise outnumber real traces by orders of magnitude. The relay's actual
 * sends still show up: they join the trace of the request that wrote the event (see OutboxTracing).
 */
@Configuration
public class ObservationFilters {

    @Bean
    public ObservationPredicate noPollerOrProbeTraces() {
        return (name, context) -> {
            if ("tasks.scheduled.execution".equals(name)) {
                return false;
            }
            return !(context instanceof ServerRequestObservationContext request
                    && request.getCarrier().getRequestURI().startsWith("/actuator"));
        };
    }
}
