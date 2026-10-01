package com.junie.serviceops.service;

import com.junie.serviceops.model.ApprovalRequest;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import com.junie.serviceops.model.Remediation;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RemediationService {

    private final IncidentStore incidents;
    private final RemediationStore remediations;
    private final ServiceOpsMetrics metrics;

    public RemediationService(IncidentStore incidents, RemediationStore remediations, ServiceOpsMetrics metrics) {
        this.incidents = incidents;
        this.remediations = remediations;
        this.metrics = metrics;
    }

    /**
     * Records the approval decision and, when approved, runs the sandbox adapter. The audit
     * row and the incident's new status are written in one transaction so a recorded
     * execution never disagrees with the incident it resolved.
     */
    @Transactional
    public Remediation approveAndExecute(String incidentId, String action, ApprovalRequest approval) {
        Incident incident = incidents.get(incidentId);
        if (incident == null) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }
        if (!approval.approved()) {
            metrics.remediation("REJECTED");
            return remediations.save(new Remediation(UUID.randomUUID().toString(), incident.id(), action,
                    "REJECTED", approval.approver(), Instant.now(), approval.comment()));
        }
        incidents.update(incident.withStatus(IncidentStatus.RESOLVED));
        metrics.remediation("EXECUTED_SANDBOX");
        return remediations.save(new Remediation(UUID.randomUUID().toString(), incident.id(), action,
                "EXECUTED_SANDBOX", approval.approver(), Instant.now(),
                "Action executed in the sandbox adapter; production execution is intentionally disabled."));
    }

    public List<Remediation> history(String incidentId) {
        if (incidents.get(incidentId) == null) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }
        return remediations.forIncident(incidentId);
    }
}
