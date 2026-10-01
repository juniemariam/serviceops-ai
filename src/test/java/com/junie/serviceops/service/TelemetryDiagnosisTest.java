package com.junie.serviceops.service;

import com.junie.serviceops.model.Diagnosis;
import com.junie.serviceops.model.Evidence;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.ServiceTelemetry;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import com.junie.serviceops.telemetry.StubTelemetryAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryDiagnosisTest {

    private final ServiceCatalog catalog = new InMemoryServiceCatalog();
    private final ServiceOpsMetrics metrics = new ServiceOpsMetrics(new SimpleMeterRegistry());
    private final StubTelemetryAdapter telemetry = new StubTelemetryAdapter();
    private final IncidentStore store = new InMemoryIncidentStore();
    private final DiagnosisService diagnosis = new DiagnosisService(store,
            new ClassifierClient(RestClient.builder(), "http://localhost:65530", metrics),
            new InMemoryKnowledgeService(),
            new DependencyGraphService(catalog, 5), catalog,
            new InMemoryDeploymentStore(), telemetry, metrics, Duration.ofHours(2));

    private Diagnosis diagnose(String service) {
        Incident incident = diagnosis.create(new Incident(null, "Checkout unavailable", "pods crashlooping",
                service, "dev", null, null, null, null, null));
        return diagnosis.diagnose(incident.id());
    }

    private List<Evidence> telemetryEvidence(Diagnosis d) {
        return d.evidence().stream().filter(e -> e.sourceType().equals("TELEMETRY")).toList();
    }

    @Test
    void anUnhealthyIncidentServiceBecomesEvidence() {
        telemetry.unhealthy("checkout", true, 0.42);

        List<Evidence> found = telemetryEvidence(diagnose("checkout"));

        assertEquals(1, found.size());
        assertTrue(found.get(0).excerpt().contains("checkout is reporting unhealthy telemetry"));
        assertTrue(found.get(0).excerpt().contains("42.0%"));
    }

    @Test
    void anUnhealthyDependencyBecomesEvidenceAndOutranksTheServiceItself() {
        telemetry.unhealthy("checkout", true, 0.10);
        telemetry.unhealthy("payments", false, null);

        List<Evidence> found = telemetryEvidence(diagnose("checkout"));

        assertEquals(2, found.size());
        Evidence dependency = found.stream()
                .filter(e -> e.sourceId().equals("telemetry-payments")).findFirst().orElseThrow();
        Evidence own = found.stream()
                .filter(e -> e.sourceId().equals("telemetry-checkout")).findFirst().orElseThrow();
        assertTrue(dependency.relevance() > own.relevance(),
                "a failing dependency is the more specific finding and should rank higher");
        assertTrue(dependency.excerpt().contains("a dependency of this incident's service"));
        assertTrue(dependency.excerpt().contains("scrape target is down"));
    }

    @Test
    void healthyServicesAreOmittedSoTheSickOneStandsOut() {
        telemetry.healthy("checkout");
        telemetry.healthy("payments");
        telemetry.healthy("checkout-db");
        telemetry.unhealthy("fraud", true, 0.9);

        List<Evidence> found = telemetryEvidence(diagnose("checkout"));

        assertEquals(1, found.size(), "only the unhealthy dependency should appear");
        assertEquals("telemetry-fraud", found.get(0).sourceId());
    }

    @Test
    void servicesWithoutConfiguredTelemetryProduceNoEvidence() {
        // The stub returns "unavailable" for anything not explicitly set.
        assertTrue(telemetryEvidence(diagnose("checkout")).isEmpty());
    }

    @Test
    void onlyDirectDependenciesAreReadNotTheWholeTransitiveTree() {
        // identity-db is transitively below checkout (checkout -> payments -> identity ->
        // identity-db) but is not a direct dependency, so it must not be queried.
        telemetry.unhealthy("identity-db", false, null);

        assertTrue(telemetryEvidence(diagnose("checkout")).isEmpty(),
                "reading the full transitive tree would make diagnosis cost grow with graph size");
    }

    @Test
    void anIncidentWithNoServiceReadsNoTelemetry() {
        telemetry.unhealthy("checkout", false, null);

        assertTrue(telemetryEvidence(diagnose(null)).isEmpty());
    }

    @Test
    void aFailingDependencyDrivesTheNarrativeNotJustTheEvidenceList() {
        telemetry.unhealthy("payments", false, null);

        Diagnosis result = diagnose("checkout");

        assertTrue(result.summary().contains("failing dependency"), result.summary());
        assertTrue(result.likelyRootCause().contains("payments"), result.likelyRootCause());
        assertFalse(result.likelyRootCause().contains("Undetermined"),
                "the narrative must not claim ignorance while holding 0.93-relevance evidence");
    }

    @Test
    void aFailingDependencyRecommendsEscalationToItsOwnerNotARollback() {
        telemetry.unhealthy("payments", false, null);

        Diagnosis result = diagnose("checkout");

        assertTrue(result.recommendedActions().stream()
                        .anyMatch(a -> a.action().contains("platform-reliability") && a.action().contains("payments")),
                "should name the dependency's owning team: " + result.recommendedActions());
        assertTrue(result.recommendedActions().stream().noneMatch(a -> a.action().toLowerCase().contains("roll back")),
                "rolling back this service cannot fix a dependency that is down");
    }

    @Test
    void anUnhealthyOwnServiceWithNoOtherSignalIsReportedHonestly() {
        telemetry.unhealthy("checkout", true, 0.5);

        Diagnosis result = diagnose("checkout");

        assertTrue(result.summary().contains("own telemetry is unhealthy"), result.summary());
        assertTrue(result.likelyRootCause().contains("no correlated release or dependency failure"),
                result.likelyRootCause());
        assertTrue(result.confidence() > 0.62 && result.confidence() < 0.86,
                "a signal with no explanation sits between no-signal and a correlated cause");
    }

    @Test
    void withNoSignalAtAllConfidenceStaysLow() {
        Diagnosis result = diagnose("checkout");

        assertEquals(0.62, result.confidence());
        assertTrue(result.likelyRootCause().contains("Undetermined"));
    }

    @Test
    void unavailableTelemetryIsNotTreatedAsHealthy() {
        ServiceTelemetry reading = ServiceTelemetry.unavailable("x", "nothing configured");

        assertFalse(reading.indicatesProblem(), "absence of data is not evidence of a problem");
        assertFalse(reading.available());
        assertEquals(null, reading.errorRatio(), "numeric fields must stay null, never default to zero");
    }

    @Test
    void aLowErrorRatioIsNotFlaggedAsAProblem() {
        assertFalse(new ServiceTelemetry("x", true, true, 0.01, 0.1, "n").indicatesProblem());
        assertTrue(new ServiceTelemetry("x", true, true, 0.20, 0.1, "n").indicatesProblem());
        assertTrue(new ServiceTelemetry("x", true, false, 0.0, 0.1, "n").indicatesProblem(),
                "a down target is a problem even with no error ratio");
    }
}
