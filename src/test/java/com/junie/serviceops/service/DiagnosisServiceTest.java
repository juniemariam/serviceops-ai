package com.junie.serviceops.service;

import com.junie.serviceops.model.ApprovalRequest;
import com.junie.serviceops.model.Diagnosis;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import com.junie.serviceops.model.RecommendedAction;
import com.junie.serviceops.model.Remediation;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisServiceTest {

    private final ServiceOpsMetrics metrics = new ServiceOpsMetrics(new SimpleMeterRegistry());

    /** Port 65530 is unbound, so the classifier client exercises its local fallback. */
    private final ClassifierClient classifier =
            new ClassifierClient(RestClient.builder(), "http://localhost:65530", metrics);
    private final IncidentStore store = new InMemoryIncidentStore();
    private final RemediationStore remediationStore = new InMemoryRemediationStore();
    private final DeploymentStore deployments = new InMemoryDeploymentStore();
    private final DiagnosisService service = new DiagnosisService(store, classifier,
            new InMemoryKnowledgeService(), new DependencyGraphService(), deployments, metrics, Duration.ofHours(2));
    private final RemediationService remediation = new RemediationService(store, remediationStore, metrics);

    @Test
    void diagnosesDeploymentRegressionWithEvidenceAndApproval() {
        Incident incident = service.create(new Incident(null, "Payment API timeout",
                "Latency increased after deployment", "payments", "dev", null, null, null, null, null));

        Diagnosis diagnosis = service.diagnose(incident.id());

        assertEquals("P1", incident.classification().priority());
        assertTrue(diagnosis.evidence().stream().anyMatch(e -> e.sourceType().equals("SERVICE_GRAPH")));
        assertTrue(diagnosis.recommendedActions().stream().anyMatch(RecommendedAction::requiresApproval));
    }

    @Test
    void persistsIncidentAndSurfacesItInRecentAndGet() {
        Incident incident = service.create(new Incident(null, "Checkout unavailable",
                "Pods crashlooping", "checkout", null, null, null, null, null, null));

        assertEquals("dev", incident.environment(), "environment defaults to dev");
        assertEquals(incident, service.get(incident.id()));
        assertEquals(List.of(incident), service.recent(20));
    }

    @Test
    void diagnosisMovesIncidentToAwaitingApproval() {
        Incident incident = service.create(new Incident(null, "Payment API timeout",
                "Latency increased after deployment", "payments", "dev", null, null, null, null, null));

        service.diagnose(incident.id());

        assertEquals(IncidentStatus.AWAITING_APPROVAL, service.get(incident.id()).status());
    }

    @Test
    void approvalResolvesIncidentAndRecordsAnAuditRow() {
        Incident incident = service.create(new Incident(null, "Payment API timeout",
                "Latency increased after deployment", "payments", "dev", null, null, null, null, null));
        service.diagnose(incident.id());

        Remediation result = remediation.approveAndExecute(incident.id(), "rollback",
                new ApprovalRequest("on-call-engineer", true, "Approved for dev"));

        assertEquals("EXECUTED_SANDBOX", result.status());
        assertEquals(IncidentStatus.RESOLVED, service.get(incident.id()).status());
        assertEquals(List.of(result), remediation.history(incident.id()));
    }

    @Test
    void rejectionIsAuditedAndLeavesTheIncidentUnresolved() {
        Incident incident = service.create(new Incident(null, "Payment API timeout",
                "Latency increased after deployment", "payments", "dev", null, null, null, null, null));
        service.diagnose(incident.id());

        Remediation result = remediation.approveAndExecute(incident.id(), "rollback",
                new ApprovalRequest("on-call-engineer", false, "Too risky during the freeze"));

        assertEquals("REJECTED", result.status());
        assertEquals(IncidentStatus.AWAITING_APPROVAL, service.get(incident.id()).status());
        assertEquals(1, remediation.history(incident.id()).size());
    }

    @Test
    void anIncidentWithNoServiceDiagnosesInsteadOfFailing() {
        Incident incident = service.create(new Incident(null, "Unknown failure",
                "something broke", null, "dev", null, null, null, null, null));

        Diagnosis diagnosis = service.diagnose(incident.id());

        assertTrue(diagnosis.evidence().stream()
                .anyMatch(e -> e.excerpt().contains("No service was recorded")));
    }

    @Test
    void unknownIncidentIsAMissRatherThanAnError() {
        assertNull(store.get("00000000-0000-0000-0000-000000000000"));
    }
}
