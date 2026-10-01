package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;
import com.junie.serviceops.model.DeploymentEvent;
import com.junie.serviceops.model.Diagnosis;
import com.junie.serviceops.model.Evidence;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.RecommendedAction;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentCorrelationTest {

    private final ServiceOpsMetrics metrics = new ServiceOpsMetrics(new SimpleMeterRegistry());

    private final IncidentStore store = new InMemoryIncidentStore();
    private final DeploymentStore deployments = new InMemoryDeploymentStore();
    private final DeploymentIngestService deploymentIngest = new DeploymentIngestService(deployments, metrics);
    private final DiagnosisService diagnosis = new DiagnosisService(store,
            new ClassifierClient(RestClient.builder(), "http://localhost:65530", metrics),
            new InMemoryKnowledgeService(), new DependencyGraphService(),
            deployments, metrics, Duration.ofHours(2));

    private Incident incident(String service) {
        return diagnosis.create(new Incident(null, "Checkout unavailable", "pods crashlooping",
                service, "dev", null, null, null, null, null));
    }

    private DeploymentEvent event(String id, String service, String version, Instant at) {
        return new DeploymentEvent(id, service, version, "dev", DeploymentEvent.SUCCEEDED, "ci-bot", at);
    }

    @Test
    void recordsADeploymentAndIgnoresARedelivery() {
        DeploymentEvent event = event("dep-1", "checkout", "v3.1.0", Instant.now());

        assertTrue(deploymentIngest.handle(event), "first delivery is recorded");
        assertFalse(deploymentIngest.handle(event), "redelivery of the same event id is ignored");
        assertEquals(1, deployments.forServiceBetween("checkout", "dev",
                Instant.now().minus(Duration.ofHours(1)), Instant.now()).size());
    }

    @Test
    void aDeploymentInsideTheWindowBecomesEvidenceAndDrivesRollback() {
        deploymentIngest.handle(event("dep-1", "checkout", "v3.1.0", Instant.now().minus(Duration.ofMinutes(10))));

        Diagnosis result = diagnosis.diagnose(incident("checkout").id());

        Evidence deployment = result.evidence().stream()
                .filter(e -> e.sourceType().equals("DEPLOYMENT")).findFirst().orElseThrow();
        assertTrue(deployment.excerpt().contains("v3.1.0"));
        assertTrue(deployment.excerpt().contains("10 minute(s) before"));
        assertTrue(result.likelyRootCause().contains("v3.1.0"),
                "root cause should name the correlated release, not a generic string");
        assertTrue(result.recommendedActions().stream()
                .anyMatch(a -> a.action().contains("v3.1.0") && a.requiresApproval()));
    }

    @Test
    void aDeploymentOutsideTheWindowIsNotCorrelated() {
        deploymentIngest.handle(event("dep-1", "checkout", "v3.1.0", Instant.now().minus(Duration.ofHours(9))));

        Diagnosis result = diagnosis.diagnose(incident("checkout").id());

        assertTrue(result.evidence().stream().noneMatch(e -> e.sourceType().equals("DEPLOYMENT")));
        assertFalse(result.likelyRootCause().contains("v3.1.0"));
    }

    @Test
    void aDeploymentToADifferentServiceIsNotCorrelated() {
        deploymentIngest.handle(event("dep-1", "payments", "v2.4.1", Instant.now().minus(Duration.ofMinutes(5))));

        Diagnosis result = diagnosis.diagnose(incident("checkout").id());

        assertTrue(result.evidence().stream().noneMatch(e -> e.sourceType().equals("DEPLOYMENT")));
    }

    @Test
    void withoutACorrelatedDeploymentTheDiagnosisDoesNotClaimARegression() {
        Diagnosis result = diagnosis.diagnose(incident("checkout").id());

        assertTrue(result.summary().contains("No release was recorded"));
        assertTrue(result.likelyRootCause().contains("Undetermined"));
        assertTrue(result.confidence() < 0.7, "confidence must drop without a correlating release");
    }

    @Test
    void theNearestDeploymentRanksAboveAnOlderOne() {
        deploymentIngest.handle(event("dep-old", "checkout", "v3.0.0", Instant.now().minus(Duration.ofMinutes(100))));
        deploymentIngest.handle(event("dep-new", "checkout", "v3.1.0", Instant.now().minus(Duration.ofMinutes(5))));

        Diagnosis result = diagnosis.diagnose(incident("checkout").id());

        List<Evidence> deploymentEvidence = result.evidence().stream()
                .filter(e -> e.sourceType().equals("DEPLOYMENT")).toList();
        assertEquals(2, deploymentEvidence.size());
        assertTrue(deploymentEvidence.get(0).excerpt().contains("v3.1.0"));
        assertTrue(deploymentEvidence.get(0).relevance() > deploymentEvidence.get(1).relevance());
        assertTrue(result.likelyRootCause().contains("v3.1.0"), "the nearest release is the named cause");
    }

    @Test
    void anIncidentWithNoServiceCorrelatesNothingRatherThanFailing() {
        Diagnosis result = diagnosis.diagnose(incident(null).id());

        assertTrue(result.evidence().stream().noneMatch(e -> e.sourceType().equals("DEPLOYMENT")));
    }

    @Test
    void anEventMissingRequiredFieldsIsRejectedAsNonRetryable() {
        assertThrows(AlertIngestService.InvalidEventException.class,
                () -> deploymentIngest.handle(event(null, "checkout", "v1", Instant.now())));
        assertThrows(AlertIngestService.InvalidEventException.class,
                () -> deploymentIngest.handle(event("dep-2", "checkout", null, Instant.now())));
    }

    @Test
    void rollbackActionNamesTheServiceAndVersion() {
        deploymentIngest.handle(event("dep-1", "checkout", "v3.1.0", Instant.now().minus(Duration.ofMinutes(1))));

        RecommendedAction rollback = diagnosis.diagnose(incident("checkout").id()).recommendedActions().stream()
                .filter(RecommendedAction::requiresApproval).findFirst().orElseThrow();

        assertTrue(rollback.action().contains("checkout"));
        assertTrue(rollback.action().contains("v3.1.0"));
    }

    @Test
    void storeFiltersByEnvironment() {
        deploymentIngest.handle(new DeploymentEvent("dep-prod", "checkout", "v3.1.0", "prod",
                DeploymentEvent.SUCCEEDED, "ci-bot", Instant.now().minus(Duration.ofMinutes(5))));

        List<Deployment> dev = deployments.forServiceBetween("checkout", "dev",
                Instant.now().minus(Duration.ofHours(1)), Instant.now());
        List<Deployment> prod = deployments.forServiceBetween("checkout", "prod",
                Instant.now().minus(Duration.ofHours(1)), Instant.now());

        assertTrue(dev.isEmpty(), "a prod release must not explain a dev incident");
        assertEquals(1, prod.size());
    }
}
