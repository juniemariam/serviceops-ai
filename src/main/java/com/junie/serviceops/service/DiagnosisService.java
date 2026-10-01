package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;
import com.junie.serviceops.model.Diagnosis;
import com.junie.serviceops.model.Evidence;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import com.junie.serviceops.model.RecommendedAction;
import com.junie.serviceops.model.RiskLevel;
import com.junie.serviceops.model.ServiceNode;
import com.junie.serviceops.model.ServiceTelemetry;
import com.junie.serviceops.telemetry.TelemetryAdapter;
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
    private final ServiceCatalog catalog;
    private final DeploymentStore deployments;
    private final TelemetryAdapter telemetry;
    private final ServiceOpsMetrics metrics;
    private final Duration correlationWindow;

    public DiagnosisService(IncidentStore store, ClassifierClient classifier, KnowledgeService knowledge,
                            DependencyGraphService graph, ServiceCatalog catalog, DeploymentStore deployments,
                            TelemetryAdapter telemetry, ServiceOpsMetrics metrics,
                            @Value("${serviceops.diagnosis.deployment-correlation-window:PT2H}") Duration correlationWindow) {
        this.store = store;
        this.classifier = classifier;
        this.knowledge = knowledge;
        this.graph = graph;
        this.catalog = catalog;
        this.deployments = deployments;
        this.telemetry = telemetry;
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

        TelemetryFindings findings = telemetryFindings(incident.service());
        evidence.addAll(findings.evidence());

        List<Deployment> recentDeployments = correlatedDeployments(incident);
        recentDeployments.forEach(deployment -> evidence.add(deploymentEvidence(incident, deployment)));

        metrics.diagnosed(!recentDeployments.isEmpty());
        List<RecommendedAction> actions = recommend(evidence, recentDeployments, findings);
        store.update(incident.withStatus(actions.stream().anyMatch(RecommendedAction::requiresApproval)
                ? IncidentStatus.AWAITING_APPROVAL
                : IncidentStatus.INVESTIGATING));

        return new Diagnosis(incident.id(),
                summary(recentDeployments, findings),
                rootCause(recentDeployments, findings),
                evidence, actions,
                confidence(recentDeployments, findings),
                Instant.now());
    }

    /**
     * Telemetry for the incident's service and its direct dependencies.
     *
     * <p>Dependencies are included because a sick dependency is frequently the actual cause,
     * and the graph is what makes that a short, bounded list rather than every service.
     * Readings that show no problem are omitted: a diagnosis listing eight healthy
     * dependencies buries the one that is not.
     */
    private TelemetryFindings telemetryFindings(String service) {
        if (service == null) {
            return new TelemetryFindings(List.of(), null, false);
        }
        List<String> targets = new ArrayList<>();
        targets.add(service);
        targets.addAll(graph.dependenciesOf(service));

        List<Evidence> evidence = new ArrayList<>();
        String unhealthyDependency = null;
        boolean ownServiceUnhealthy = false;
        for (String target : targets) {
            ServiceTelemetry reading = telemetry.read(target);
            if (!reading.indicatesProblem()) {
                continue;
            }
            boolean isIncidentService = target.equals(service);
            if (isIncidentService) {
                ownServiceUnhealthy = true;
            } else if (unhealthyDependency == null) {
                unhealthyDependency = target;
            }
            evidence.add(new Evidence("telemetry-" + target, "TELEMETRY",
                    describe(target, reading, isIncidentService),
                    // A failing dependency outranks the incident's own service: it is the more
                    // specific finding, and it is where a responder should look first.
                    isIncidentService ? 0.82 : 0.93));
        }
        return new TelemetryFindings(evidence, unhealthyDependency, ownServiceUnhealthy);
    }

    /**
     * @param unhealthyDependency the first failing direct dependency, or null. Held separately
     *                            from the evidence list so the narrative and the recommended
     *                            action can reason over it instead of only displaying it.
     */
    private record TelemetryFindings(List<Evidence> evidence, String unhealthyDependency,
                                     boolean ownServiceUnhealthy) {
        boolean anyProblem() {
            return unhealthyDependency != null || ownServiceUnhealthy;
        }
    }

    private String describe(String service, ServiceTelemetry reading, boolean isIncidentService) {
        StringBuilder text = new StringBuilder(isIncidentService
                ? "%s is reporting unhealthy telemetry".formatted(service)
                : "%s, a dependency of this incident's service, is reporting unhealthy telemetry".formatted(service));
        if (Boolean.FALSE.equals(reading.up())) {
            text.append("; its scrape target is down");
        }
        if (reading.errorRatio() != null && reading.errorRatio() > 0.05) {
            text.append("; error ratio %.1f%%".formatted(reading.errorRatio() * 100));
        }
        if (reading.p95LatencySeconds() != null) {
            text.append("; p95 latency %.3fs".formatted(reading.p95LatencySeconds()));
        }
        return text.append(". ").append(reading.note()).toString();
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

    private List<RecommendedAction> recommend(List<Evidence> evidence, List<Deployment> recentDeployments,
                                              TelemetryFindings findings) {
        // A failing dependency outranks a correlated release: rolling back this service
        // cannot fix a dependency that is down, and doing so would waste the outage.
        if (findings.unhealthyDependency() != null) {
            String dependency = findings.unhealthyDependency();
            ServiceNode node = catalog.find(dependency);
            String owner = node == null ? "its owning team" : node.owner();
            return List.of(
                    new RecommendedAction("escalate-" + UUID.randomUUID(),
                            "Escalate to %s, the owner of %s".formatted(owner, dependency),
                            "%s is a direct dependency of this service and its telemetry is unhealthy."
                                    .formatted(dependency),
                            RiskLevel.LOW, false),
                    new RecommendedAction("investigate-" + UUID.randomUUID(),
                            "Check %s health and this service's fallback behaviour".formatted(dependency),
                            "Confirm whether the dependency failure fully explains the symptom before "
                                    + "changing this service.",
                            RiskLevel.LOW, false));
        }
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

    private String summary(List<Deployment> recentDeployments, TelemetryFindings findings) {
        if (findings.unhealthyDependency() != null) {
            return "The incident is most consistent with a failing dependency, %s."
                    .formatted(findings.unhealthyDependency());
        }
        if (!recentDeployments.isEmpty()) {
            return "The incident is most consistent with a recent deployment regression.";
        }
        if (findings.ownServiceUnhealthy()) {
            return "This service's own telemetry is unhealthy, with no correlated release or failing dependency.";
        }
        return "No release was recorded for this service inside the correlation window.";
    }

    private String rootCause(List<Deployment> recentDeployments, TelemetryFindings findings) {
        if (findings.unhealthyDependency() != null) {
            String cause = "%s, a direct dependency, is unhealthy.".formatted(findings.unhealthyDependency());
            return recentDeployments.isEmpty() ? cause
                    // Both signals present: report both rather than silently picking one.
                    : cause + " A release of %s %s also falls inside the correlation window."
                            .formatted(recentDeployments.get(0).service(), recentDeployments.get(0).version());
        }
        if (!recentDeployments.isEmpty()) {
            Deployment latest = recentDeployments.get(0);
            return "Increased dependency or database pressure following %s %s."
                    .formatted(latest.service(), latest.version());
        }
        if (findings.ownServiceUnhealthy()) {
            return "This service is degraded, but no correlated release or dependency failure explains it.";
        }
        return "Undetermined from the available evidence; no correlated deployment was found.";
    }

    /** Converging signals raise confidence; no signal at all keeps it low. */
    private double confidence(List<Deployment> recentDeployments, TelemetryFindings findings) {
        if (findings.unhealthyDependency() != null) {
            return recentDeployments.isEmpty() ? 0.84 : 0.90;
        }
        if (!recentDeployments.isEmpty()) {
            return 0.86;
        }
        return findings.anyProblem() ? 0.70 : 0.62;
    }
}
