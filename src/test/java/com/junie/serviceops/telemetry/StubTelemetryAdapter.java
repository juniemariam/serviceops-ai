package com.junie.serviceops.telemetry;

import com.junie.serviceops.model.ServiceTelemetry;

import java.util.HashMap;
import java.util.Map;

/** Lets a test decide exactly what each service reports. Defaults to "no telemetry". */
public class StubTelemetryAdapter implements TelemetryAdapter {

    private final Map<String, ServiceTelemetry> readings = new HashMap<>();

    public StubTelemetryAdapter unhealthy(String service, boolean up, Double errorRatio) {
        readings.put(service, new ServiceTelemetry(service, true, up, errorRatio, 0.25, "stub"));
        return this;
    }

    public StubTelemetryAdapter healthy(String service) {
        readings.put(service, new ServiceTelemetry(service, true, true, 0.0, 0.01, "stub"));
        return this;
    }

    @Override
    public ServiceTelemetry read(String service) {
        return readings.getOrDefault(service,
                ServiceTelemetry.unavailable(service, "No telemetry job is configured for this service."));
    }
}
