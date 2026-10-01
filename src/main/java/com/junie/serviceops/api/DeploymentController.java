package com.junie.serviceops.api;

import com.junie.serviceops.model.Deployment;
import com.junie.serviceops.service.DeploymentStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Read-only view of ingested deployments; they arrive over Kafka, never over HTTP. */
@RestController
@Validated
@RequestMapping("/api/v1/services/{service}/deployments")
public class DeploymentController {

    private final DeploymentStore deployments;

    public DeploymentController(DeploymentStore deployments) {
        this.deployments = deployments;
    }

    @GetMapping
    public List<Deployment> recent(@PathVariable String service,
                                   @RequestParam(required = false) String environment,
                                   @RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours) {
        Instant now = Instant.now();
        return deployments.forServiceBetween(service, environment, now.minus(Duration.ofHours(hours)), now);
    }
}
