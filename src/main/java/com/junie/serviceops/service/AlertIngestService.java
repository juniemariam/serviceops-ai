package com.junie.serviceops.service;

import com.junie.serviceops.model.AlertEvent;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Turns alert events into incidents.
 *
 * <p>Deliberately holds no Kafka types: the listener owns delivery concerns, this owns the
 * ingestion rules, and the rules stay unit-testable without a broker.
 */
@Service
public class AlertIngestService {

    private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);

    private final IncidentStore incidents;
    private final DiagnosisService diagnosis;
    private final ServiceOpsMetrics metrics;

    public AlertIngestService(IncidentStore incidents, DiagnosisService diagnosis, ServiceOpsMetrics metrics) {
        this.incidents = incidents;
        this.diagnosis = diagnosis;
        this.metrics = metrics;
    }

    /**
     * Applies one alert event.
     *
     * @return the affected incident, or empty when a RESOLVED alert matched nothing open
     * @throws InvalidEventException when the payload cannot be processed at all; retrying it
     *                               would fail identically, so the listener routes it to the
     *                               dead letter topic instead of blocking the partition
     */
    @Transactional
    public Optional<Incident> handle(AlertEvent event) {
        if (event == null || event.fingerprint() == null || event.fingerprint().isBlank()) {
            throw new InvalidEventException("Alert event is missing a fingerprint");
        }
        return event.isResolved() ? resolve(event) : fire(event);
    }

    private Optional<Incident> fire(AlertEvent event) {
        Incident open = incidents.findOpenByDedupKey(event.fingerprint());
        if (open != null) {
            // A firing alert repeats for as long as the condition holds. Collapsing onto the
            // existing incident is what keeps a flapping alert from opening hundreds.
            log.debug("Alert {} already tracked by incident {}", event.fingerprint(), open.id());
            metrics.alertIngested("collapsed");
            return Optional.of(open);
        }
        if (event.summary() == null || event.summary().isBlank()) {
            throw new InvalidEventException("Firing alert " + event.fingerprint() + " has no summary");
        }
        Incident created = diagnosis.register(new Incident(
                null,
                event.summary(),
                event.description() == null ? event.summary() : event.description(),
                event.service(),
                event.environment() == null ? "dev" : event.environment(),
                event.firedAt() == null ? Instant.now() : event.firedAt(),
                IncidentStatus.OPEN,
                null,
                Incident.SOURCE_ALERT,
                event.fingerprint()));
        log.info("Opened incident {} from alert {}", created.id(), event.fingerprint());
        metrics.alertIngested("opened");
        return Optional.of(created);
    }

    private Optional<Incident> resolve(AlertEvent event) {
        Incident open = incidents.findOpenByDedupKey(event.fingerprint());
        if (open == null) {
            // Normal, not an error: the alert resolved after a human already closed the
            // incident, or it never fired through this consumer at all.
            log.debug("Resolved alert {} matched no open incident", event.fingerprint());
            metrics.alertIngested("ignored");
            return Optional.empty();
        }
        Incident resolved = incidents.update(open.withStatus(IncidentStatus.RESOLVED));
        log.info("Resolved incident {} from alert {}", resolved.id(), event.fingerprint());
        metrics.alertIngested("resolved");
        return Optional.of(resolved);
    }

    /** Marks a payload that will never succeed, however many times it is redelivered. */
    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String message) {
            super(message);
        }
    }
}
