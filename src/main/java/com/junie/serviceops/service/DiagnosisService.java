package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;
import com.junie.serviceops.model.Diagnosis;
import com.junie.serviceops.model.Evidence;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import com.junie.serviceops.model.RecommendedAction;
import com.junie.serviceops.model.RiskLevel;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DiagnosisService {

    private final IncidentStore store;
    private final ClassifierClient classifier;
    private final KnowledgeService knowledge;
    private final DependencyGraphService graph;
    private final DeploymentStore deployments;
    private final ServiceOpsMetrics metrics;
    private final Duration correlationWindow;

    public DiagnosisService(IncidentStore store, ClassifierClient classifier, KnowledgeService knowledge,
                            DependencyGraphService graph, DeploymentStore deployments, ServiceOpsMetrics metrics,
                            @Value("${serviceops.diagnosis.deployment-correlation-window:PT2H}") Duration correlationWindow) {
        this.store = store;
        this.classifier = classifier;
        this.knowledge = knowledge;
        this.graph = graph;
        this.deployments = deployments;
        this.metrics = metrics;
        this.correlationWindow = correlationWindow;
    }

    /** Creates an incident from a REST request. Provenance is forced, never taken from the caller. */
    @Transactional
    public Incident create(Incident request) {
        return register(new Incident(null, request.title(), request.description(), request.service(),
                request.environment() == null ? "dev" : request.environment(), Instant.now(),
                IncidentStatus.OPEN, null, Incident.SOURCE_API, null));
    }

    /** Classifies and stores an already-normalized incident, whatever its source. */
    @Transactional
    public Incident register(Incident normalized) {
        Incident saved = store.save(normalized.withClassification(classifier.classify(normalized)));
        metrics.incidentOpened(saved.source());
        return saved;
    }

    public Incident get(String id) {
        Incident incident = store.get(id);
        if (incident == null) {
            throw new IllegalArgumentException("Incident not found: " + id);
        }
        return incident;
    }

    public List<Incident> recent(int limit) {
        return store.recent(limit);
    }

    @Transactional
    public Diagnosis diagnose(String id) {
        Timer.Sample sample = metrics.startDiagnosis();
        try {
            return doDiagnose(id);
        } finally {
            metrics.stopDiagnosis(sample);
        }
    }

    private Diagnosis doDiagnose(String id) {
        Incident incident = get(id);
        List<Evidence> evidence = new ArrayList<>(
                knowledge.retrieve(incident.title() + " " + incident.description(), incident.service()));
        evidence.add(new Evidence("dependency-graph-" + incident.service(), "SERVICE_GRAPH",
                graph.explainImpact(incident.service()), 0.88));

        List<Deployment> recentDeployments = correlatedDeployments(incident);
        recentDeployments.forEach(deployment -> evidence.add(deploymentEvidence(incident, deployment)));

        metrics.diagnosed(!recentDeployments.isEmpty());
        List<RecommendedAction> actions = recommend(evidence, recentDeployments);
        store.update(incident.withStatus(actions.stream().anyMatch(RecommendedAction::requiresApproval)
                ? IncidentStatus.AWAITING_APPROVAL
                : IncidentStatus.INVESTIGATING));

        return new Diagnosis(incident.id(),
                summary(recentDeployments),
                rootCause(recentDeployments),
                evidence, actions,
                recentDeployments.isEmpty() ? 0.62 : 0.86,
                Instant.now());
    }

    /**
     * Deployments to the incident's own service shortly before it opened. Only the service
     * itself is considered, not its dependencies: a dependency's release is a weaker signal
     * and would need its own ranking rather than being mixed in at equal weight.
     */
    private List<Deployment> correlatedDeployments(Incident incident) {
        if (incident.service() == null) {
            return List.of();
        }
        Instant openedAt = incident.createdAt() == null ? Instant.now() : incident.createdAt();
        return deployments.forServiceBetween(incident.service(), incident.environment(),
                openedAt.minus(correlationWindow), openedAt);
    }

    /** Closer deployments score higher, decaying linearly across the correlation window. */
    private Evidence deploymentEvidence(Incident incident, Deployment deployment) {
        Instant openedAt = incident.createdAt() == null ? Instant.now() : incident.createdAt();
        long minutesBefore = Math.max(0, ChronoUnit.MINUTES.between(deployment.deployedAt(), openedAt));
        double windowMinutes = Math.max(1, correlationWindow.toMinutes());
        double relevance = Math.max(0.5, 0.95 - (minutesBefore / windowMinutes) * 0.45);
        return new Evidence("deployment-" + deployment.eventId(), "DEPLOYMENT",
                "%s %s (%s) was deployed to %s by %s, %d minute(s) before the incident opened."
                        .formatted(deployment.service(), deployment.version(), deployment.status(),
                                deployment.environment(),
                                deployment.deployedBy() == null ? "an unknown actor" : deployment.deployedBy(),
                                minutesBefore),
                relevance);
    }

    private List<RecommendedAction> recommend(List<Evidence> evidence, List<Deployment> recentDeployments) {
        if (!recentDeployments.isEmpty()) {
            Deployment latest = recentDeployments.get(0);
            return List.of(
                    new RecommendedAction("rollback-" + UUID.randomUUID(),
                            "Roll back %s to the release preceding %s in the sandbox"
                                    .formatted(latest.service(), latest.version()),
                            "%s was deployed to %s within the correlation window before this incident."
                                    .formatted(latest.version(), latest.environment()),
                            RiskLevel.MEDIUM, true),
                    new RecommendedAction("article-" + UUID.randomUUID(),
                            "Draft a knowledge article from this diagnosis",
                            "Preserve the verified resolution for future responders.",
                            RiskLevel.LOW, true));
        }
        // Without a correlated deployment, fall back to the retrieved corpus: a matching past
        // incident that was fixed by a rollback is weaker evidence, but still evidence.
        boolean corpusSuggestsRollback = evidence.stream()
                .anyMatch(e -> e.excerpt().toLowerCase().contains("rollback"));
        if (corpusSuggestsRollback) {
            return List.of(
                    new RecommendedAction("rollback-" + UUID.randomUUID(),
                            "Rollback the latest deployment in the sandbox",
                            "A matching past incident links this symptom to a release regression.",
                            RiskLevel.MEDIUM, true),
                    new RecommendedAction("article-" + UUID.randomUUID(),
                            "Draft a knowledge article from this diagnosis",
                            "Preserve the verified resolution for future responders.",
                            RiskLevel.LOW, true));
        }
        return List.of(new RecommendedAction("investigate-" + UUID.randomUUID(),
                "Collect logs and dependency metrics",
                "Insufficient evidence for a safe remediation.", RiskLevel.LOW, false));
    }

    private String summary(List<Deployment> recentDeployments) {
        return recentDeployments.isEmpty()
                ? "No release was recorded for this service inside the correlation window."
                : "The incident is most consistent with a recent deployment regression.";
    }

    private String rootCause(List<Deployment> recentDeployments) {
        if (recentDeployments.isEmpty()) {
            return "Undetermined from the available evidence; no correlated deployment was found.";
        }
        Deployment latest = recentDeployments.get(0);
        return "Increased dependency or database pressure following %s %s."
                .formatted(latest.service(), latest.version());
    }
}
