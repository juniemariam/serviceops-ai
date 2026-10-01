package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;
import com.junie.serviceops.model.DeploymentEvent;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeploymentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentIngestService.class);

    private final DeploymentStore deployments;
    private final ServiceOpsMetrics metrics;

    public DeploymentIngestService(DeploymentStore deployments, ServiceOpsMetrics metrics) {
        this.deployments = deployments;
        this.metrics = metrics;
    }

    /**
     * @return true when the deployment was recorded, false when it was a duplicate delivery
     * @throws AlertIngestService.InvalidEventException when required fields are missing
     */
    @Transactional
    public boolean handle(DeploymentEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new AlertIngestService.InvalidEventException("Deployment event is missing an event id");
        }
        if (event.service() == null || event.service().isBlank() || event.version() == null || event.version().isBlank()) {
            throw new AlertIngestService.InvalidEventException(
                    "Deployment event " + event.eventId() + " is missing a service or version");
        }
        boolean recorded = deployments.saveIfAbsent(new Deployment(
                UUID.randomUUID().toString(),
                event.eventId(),
                event.service(),
                event.version(),
                event.environment() == null ? "dev" : event.environment(),
                event.status() == null ? DeploymentEvent.SUCCEEDED : event.status(),
                event.deployedBy(),
                event.deployedAt() == null ? Instant.now() : event.deployedAt(),
                Instant.now()));
        metrics.deploymentIngested(recorded ? "recorded" : "duplicate");
        if (recorded) {
            log.info("Recorded deployment {} of {} {}", event.eventId(), event.service(), event.version());
        } else {
            log.debug("Ignored duplicate deployment event {}", event.eventId());
        }
        return recorded;
    }
}
