package com.junie.serviceops.telemetry;

import com.junie.serviceops.model.ServiceTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when no telemetry backend is configured, and by the {@code memory} profile. */
@Component
@ConditionalOnProperty(name = "serviceops.telemetry.enabled", havingValue = "false")
public class NoopTelemetryAdapter implements TelemetryAdapter {

    @Override
    public ServiceTelemetry read(String service) {
        return ServiceTelemetry.unavailable(service, "Telemetry collection is disabled.");
    }
}
