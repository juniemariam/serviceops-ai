package com.junie.serviceops.telemetry;

import com.junie.serviceops.model.ServiceTelemetry;

public interface TelemetryAdapter {

    /**
     * Reads current health signals for a service.
     *
     * <p>Never throws and never fabricates. A service with no telemetry configured, or a
     * backend that cannot be reached, comes back as {@link ServiceTelemetry#unavailable}
     * with an explanatory note — distinguishable from a healthy reading.
     */
    ServiceTelemetry read(String service);
}
