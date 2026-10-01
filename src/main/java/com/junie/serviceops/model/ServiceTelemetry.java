package com.junie.serviceops.model;

/**
 * A point-in-time read of a service's health signals.
 *
 * @param available false when no telemetry is configured or the backend could not be
 *                  reached. The numeric fields are null in that case; they are never
 *                  defaulted to zero, because "zero errors" and "no data" must not look
 *                  the same to a responder or to the diagnosis logic.
 * @param up        null when unknown, otherwise whether the scrape target is reachable
 */
public record ServiceTelemetry(
        String service,
        boolean available,
        Boolean up,
        Double errorRatio,
        Double p95LatencySeconds,
        String note) {

    public static ServiceTelemetry unavailable(String service, String note) {
        return new ServiceTelemetry(service, false, null, null, null, note);
    }

    /** True when there is a signal worth putting in front of a responder. */
    public boolean indicatesProblem() {
        if (!available) {
            return false;
        }
        return Boolean.FALSE.equals(up) || (errorRatio != null && errorRatio > 0.05);
    }
}
