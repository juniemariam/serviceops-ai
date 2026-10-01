package com.junie.serviceops.service;

import com.junie.serviceops.model.AlertEvent;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.junie.serviceops.telemetry.StubTelemetryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertIngestServiceTest {

    private final ServiceCatalog catalog = new InMemoryServiceCatalog();
    private final ServiceOpsMetrics metrics = new ServiceOpsMetrics(new SimpleMeterRegistry());
    private final StubTelemetryAdapter telemetry = new StubTelemetryAdapter();

    private final IncidentStore store = new InMemoryIncidentStore();
    private final DiagnosisService diagnosis = new DiagnosisService(store,
            new ClassifierClient(RestClient.builder(), "http://localhost:65530", metrics),
            new InMemoryKnowledgeService(), new DependencyGraphService(catalog, 5), catalog,
            new InMemoryDeploymentStore(), telemetry, metrics, Duration.ofHours(2));
    private final AlertIngestService ingest = new AlertIngestService(store, diagnosis, metrics);

    private AlertEvent firing(String fingerprint) {
        return new AlertEvent("evt-" + fingerprint, fingerprint, AlertEvent.FIRING,
                "Payment API timeout", "p95 latency above threshold", "payments", "dev", "critical", Instant.now());
    }

    @Test
    void opensAnIncidentFromAFiringAlert() {
        Incident incident = ingest.handle(firing("payments-p95")).orElseThrow();

        assertEquals("Payment API timeout", incident.title());
        assertEquals(Incident.SOURCE_ALERT, incident.source());
        assertEquals("payments-p95", incident.dedupKey());
        assertEquals(IncidentStatus.OPEN, incident.status());
    }

    @Test
    void collapsesARepeatedlyFiringAlertOntoOneIncident() {
        Incident first = ingest.handle(firing("payments-p95")).orElseThrow();
        Incident second = ingest.handle(firing("payments-p95")).orElseThrow();
        Incident third = ingest.handle(firing("payments-p95")).orElseThrow();

        assertEquals(first.id(), second.id());
        assertEquals(first.id(), third.id());
        assertEquals(1, store.recent(10).size(), "a flapping alert must not open three incidents");
    }

    @Test
    void distinctFingerprintsOpenDistinctIncidents() {
        Incident payments = ingest.handle(firing("payments-p95")).orElseThrow();
        Incident checkout = ingest.handle(firing("checkout-5xx")).orElseThrow();

        assertNotEquals(payments.id(), checkout.id());
        assertEquals(2, store.recent(10).size());
    }

    @Test
    void resolvingAlertClosesTheOpenIncident() {
        Incident opened = ingest.handle(firing("payments-p95")).orElseThrow();

        AlertEvent resolved = new AlertEvent("evt-2", "payments-p95", AlertEvent.RESOLVED,
                "Payment API timeout", "recovered", "payments", "dev", "critical", Instant.now());
        Incident closed = ingest.handle(resolved).orElseThrow();

        assertEquals(opened.id(), closed.id());
        assertEquals(IncidentStatus.RESOLVED, store.get(opened.id()).status());
    }

    @Test
    void aResolvedAlertWithNothingOpenIsIgnored() {
        AlertEvent resolved = new AlertEvent("evt-9", "never-fired", AlertEvent.RESOLVED,
                "n/a", "n/a", "payments", "dev", "warning", Instant.now());

        assertEquals(Optional.empty(), ingest.handle(resolved));
    }

    @Test
    void aRecurrenceAfterResolutionOpensANewIncident() {
        Incident first = ingest.handle(firing("payments-p95")).orElseThrow();
        ingest.handle(new AlertEvent("evt-2", "payments-p95", AlertEvent.RESOLVED,
                "Payment API timeout", "recovered", "payments", "dev", "critical", Instant.now()));

        Incident second = ingest.handle(firing("payments-p95")).orElseThrow();

        assertNotEquals(first.id(), second.id(), "a genuine recurrence is a new incident");
        assertEquals(IncidentStatus.RESOLVED, store.get(first.id()).status());
    }

    @Test
    void aPayloadWithoutAFingerprintIsRejectedAsNonRetryable() {
        AlertEvent noFingerprint = new AlertEvent("evt-1", null, AlertEvent.FIRING,
                "summary", "description", "payments", "dev", "critical", Instant.now());

        assertThrows(AlertIngestService.InvalidEventException.class, () -> ingest.handle(noFingerprint));
    }

    @Test
    void aFiringAlertWithoutASummaryIsRejectedAsNonRetryable() {
        AlertEvent noSummary = new AlertEvent("evt-1", "fp", AlertEvent.FIRING,
                "  ", "description", "payments", "dev", "critical", Instant.now());

        assertThrows(AlertIngestService.InvalidEventException.class, () -> ingest.handle(noSummary));
    }

    @Test
    void anIngestedIncidentIsDiagnosableLikeAnyOther() {
        Incident incident = ingest.handle(firing("payments-p95")).orElseThrow();

        assertTrue(diagnosis.diagnose(incident.id()).evidence().stream()
                .anyMatch(e -> e.sourceType().equals("SERVICE_GRAPH")));
    }
}
