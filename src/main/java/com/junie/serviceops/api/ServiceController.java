package com.junie.serviceops.api;

import com.junie.serviceops.model.ServiceImpact;
import com.junie.serviceops.model.ServiceNode;
import com.junie.serviceops.model.ServiceTelemetry;
import com.junie.serviceops.service.DependencyGraphService;
import com.junie.serviceops.service.ServiceCatalog;
import com.junie.serviceops.telemetry.TelemetryAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final DependencyGraphService graph;
    private final ServiceCatalog catalog;
    private final TelemetryAdapter telemetry;

    public ServiceController(DependencyGraphService graph, ServiceCatalog catalog, TelemetryAdapter telemetry) {
        this.graph = graph;
        this.catalog = catalog;
        this.telemetry = telemetry;
    }

    @GetMapping
    public List<ServiceNode> all() {
        return catalog.all();
    }

    @GetMapping("/{service}")
    public ServiceNode get(@PathVariable String service) {
        ServiceNode node = catalog.find(service);
        if (node == null) {
            throw new IllegalArgumentException("Service not found: " + service);
        }
        return node;
    }

    @GetMapping("/{service}/dependencies")
    public List<String> dependencies(@PathVariable String service) {
        return graph.dependenciesOf(service);
    }

    @GetMapping("/{service}/dependents")
    public List<String> dependents(@PathVariable String service) {
        return graph.dependentsOf(service);
    }

    /** Transitive dependencies, blast radius, and the critical-edge-only subset. */
    @GetMapping("/{service}/impact")
    public ServiceImpact impact(@PathVariable String service) {
        return graph.impactOf(service);
    }

    @GetMapping("/{service}/telemetry")
    public ServiceTelemetry telemetry(@PathVariable String service) {
        return telemetry.read(service);
    }
}
