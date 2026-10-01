package com.junie.serviceops.service;

import com.junie.serviceops.model.ServiceEdge;
import com.junie.serviceops.model.ServiceImpact;
import com.junie.serviceops.model.ServiceNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Traversal over the service graph in {@code services} / {@code service_dependencies}.
 *
 * <p>Both directions matter and answer different questions. Downstream ("what does this
 * depend on") narrows where a root cause might be. Upstream ("what depends on this") is the
 * blast radius, which is what decides incident priority.
 */
@Service
public class DependencyGraphService {

    private final ServiceCatalog catalog;
    private final int maxDepth;

    public DependencyGraphService(ServiceCatalog catalog,
                                  @Value("${serviceops.graph.max-depth:5}") int maxDepth) {
        this.catalog = catalog;
        this.maxDepth = maxDepth;
    }

    /** Direct dependencies only, as service names. */
    public List<String> dependenciesOf(String service) {
        return catalog.dependenciesOf(service).stream().map(ServiceEdge::toService).toList();
    }

    /** Direct dependents only, as service names. */
    public List<String> dependentsOf(String service) {
        return catalog.dependentsOf(service).stream().map(ServiceEdge::fromService).toList();
    }

    /**
     * Transitive impact in both directions.
     *
     * <p>Real topologies contain cycles, so traversal tracks visited nodes rather than
     * assuming a DAG; without that, a cycle such as A→B→A would not terminate. Depth is
     * capped and {@code truncated} reports when the cap was hit, so a caller can tell a
     * partial answer from a complete one.
     */
    public ServiceImpact impactOf(String service) {
        if (service == null || catalog.find(service) == null) {
            return new ServiceImpact(service, List.of(), List.of(), List.of(), false);
        }
        Traversal down = walk(service, s -> catalog.dependenciesOf(s).stream().map(ServiceEdge::toService).toList());
        Traversal up = walk(service, s -> catalog.dependentsOf(s).stream().map(ServiceEdge::fromService).toList());
        Traversal criticalUp = walk(service, s -> catalog.dependentsOf(s).stream()
                .filter(ServiceEdge::critical)
                .map(ServiceEdge::fromService)
                .toList());
        return new ServiceImpact(service,
                List.copyOf(down.visited()),
                List.copyOf(up.visited()),
                List.copyOf(criticalUp.visited()),
                down.truncated() || up.truncated() || criticalUp.truncated());
    }

    /**
     * A sentence for a responder, and the text that lands in the diagnosis as
     * {@code SERVICE_GRAPH} evidence.
     */
    public String explainImpact(String service) {
        if (service == null) {
            return "No service was recorded for this incident, so no dependency impact could be derived.";
        }
        ServiceNode node = catalog.find(service);
        if (node == null) {
            return "%s is not registered in the service catalog, so no dependency impact could be derived."
                    .formatted(service);
        }
        ServiceImpact impact = impactOf(service);
        StringBuilder text = new StringBuilder("%s is owned by %s (tier %d)."
                .formatted(node.name(), node.owner(), node.tier()));
        if (impact.dependencies().isEmpty()) {
            text.append(" It has no registered dependencies.");
        } else {
            text.append(" It depends on ").append(String.join(", ", impact.dependencies())).append('.');
        }
        if (impact.dependents().isEmpty()) {
            text.append(" Nothing registered depends on it, so the blast radius is limited to itself.");
        } else {
            text.append(" Failure would affect ").append(String.join(", ", impact.dependents()));
            if (!impact.criticalPath().isEmpty()) {
                text.append(", critically ").append(String.join(", ", impact.criticalPath()));
            }
            text.append('.');
        }
        if (impact.truncated()) {
            text.append(" Traversal stopped at the depth limit, so this is partial.");
        }
        return text.toString();
    }

    private record Traversal(Set<String> visited, boolean truncated) {}

    /** Breadth-first so the result is ordered by distance from the starting service. */
    private Traversal walk(String start, Function<String, List<String>> neighbours) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(List.of(start));
        Set<String> seen = new LinkedHashSet<>(List.of(start));
        boolean truncated = false;
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            while (!frontier.isEmpty()) {
                String current = frontier.poll();
                for (String neighbour : neighbours.apply(current)) {
                    if (seen.add(neighbour)) {
                        visited.add(neighbour);
                        next.add(neighbour);
                    }
                }
            }
            frontier.addAll(next);
        }
        if (!frontier.isEmpty()) {
            truncated = true;
        }
        return new Traversal(visited, truncated);
    }
}
