package com.junie.serviceops.model;

import java.time.Instant;

/**
 * An alert from a monitoring system, consumed from the alerts topic.
 *
 * @param eventId     producer-assigned id, used only for logging; incident de-duplication
 *                    keys off {@code fingerprint}, since a flapping alert sends many
 *                    distinct events for one condition
 * @param fingerprint stable identity of the alerting condition
 * @param status      FIRING or RESOLVED; a RESOLVED alert closes the matching incident
 */
public record AlertEvent(
        String eventId,
        String fingerprint,
        String status,
        String summary,
        String description,
        String service,
        String environment,
        String severity,
        Instant firedAt) {

    public static final String FIRING = "FIRING";
    public static final String RESOLVED = "RESOLVED";

    public boolean isResolved() {
        return RESOLVED.equalsIgnoreCase(status);
    }
}
