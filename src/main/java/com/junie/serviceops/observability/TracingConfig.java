package com.junie.serviceops.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true", matchIfMissing = true)
public class TracingConfig {

    /**
     * Drops observations for the actuator endpoints.
     *
     * <p>Prometheus scrapes {@code /actuator/prometheus} every 10 seconds, and the health probe
     * runs on its own schedule. Tracing those produces thousands of identical single-span
     * traces per day per replica, which buries the handful of traces an incident
     * investigation actually needs and inflates trace storage for no benefit.
     */
    @Bean
    ObservationPredicate excludeActuatorEndpointsFromTracing() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !serverContext.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
