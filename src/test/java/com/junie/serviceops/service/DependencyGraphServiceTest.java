package com.junie.serviceops.service;

import com.junie.serviceops.model.ServiceEdge;
import com.junie.serviceops.model.ServiceImpact;
import com.junie.serviceops.model.ServiceNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphServiceTest {

    private final DependencyGraphService graph = new DependencyGraphService(new InMemoryServiceCatalog(), 5);

    /** Catalog with an A→B→C→A cycle, to prove traversal terminates on real topologies. */
    private static ServiceCatalog cyclic() {
        Map<String, ServiceNode> nodes = Map.of(
                "a", new ServiceNode("a", "team", 1, null, ""),
                "b", new ServiceNode("b", "team", 1, null, ""),
                "c", new ServiceNode("c", "team", 1, null, ""));
        List<ServiceEdge> edges = List.of(
                new ServiceEdge("a", "b", true),
                new ServiceEdge("b", "c", true),
                new ServiceEdge("c", "a", true));
        return new ServiceCatalog() {
            public ServiceNode find(String name) { return nodes.get(name); }
            public List<ServiceNode> all() { return List.copyOf(nodes.values()); }
            public List<ServiceEdge> dependenciesOf(String s) {
                return edges.stream().filter(e -> Objects.equals(e.fromService(), s)).toList();
            }
            public List<ServiceEdge> dependentsOf(String s) {
                return edges.stream().filter(e -> Objects.equals(e.toService(), s)).toList();
            }
        };
    }

    /** Deep chain a0→a1→…→a9, to prove the depth cap engages and is reported. */
    private static ServiceCatalog chain(int length) {
        return new ServiceCatalog() {
            public ServiceNode find(String name) {
                return name != null && name.startsWith("a") ? new ServiceNode(name, "team", 1, null, "") : null;
            }
            public List<ServiceNode> all() { return List.of(); }
            public List<ServiceEdge> dependenciesOf(String s) {
                int i = Integer.parseInt(s.substring(1));
                return i + 1 < length ? List.of(new ServiceEdge(s, "a" + (i + 1), true)) : List.of();
            }
            public List<ServiceEdge> dependentsOf(String s) {
                int i = Integer.parseInt(s.substring(1));
                return i > 0 ? List.of(new ServiceEdge("a" + (i - 1), s, true)) : List.of();
            }
        };
    }

    @Test
    void returnsDirectDependenciesFromTheCatalog() {
        assertEquals(List.of("payments", "fraud", "checkout-db"), graph.dependenciesOf("checkout"));
    }

    @Test
    void returnsDirectDependents() {
        assertEquals(List.of("checkout"), graph.dependentsOf("payments"));
        assertEquals(List.of("payments"), graph.dependentsOf("identity"));
    }

    @Test
    void transitiveDependenciesFollowTheChain() {
        ServiceImpact impact = graph.impactOf("checkout");

        assertTrue(impact.dependencies().containsAll(
                List.of("payments", "fraud", "checkout-db", "payments-db", "identity", "fraud-db", "identity-db")),
                "checkout should reach the whole downstream tree, got " + impact.dependencies());
        assertFalse(impact.truncated());
    }

    @Test
    void blastRadiusIsTheTransitiveUpstreamSet() {
        ServiceImpact impact = graph.impactOf("identity-db");

        assertEquals(List.of("identity", "payments", "checkout"), impact.dependents(),
                "breadth-first order means nearest dependents come first");
    }

    @Test
    void criticalPathExcludesNonCriticalEdges() {
        // checkout depends on fraud non-critically, so fraud's failure must not place
        // checkout on the critical path.
        ServiceImpact impact = graph.impactOf("fraud-db");

        assertTrue(impact.dependents().contains("checkout"), "checkout is still affected");
        assertFalse(impact.criticalPath().contains("checkout"),
                "but not critically, because checkout -> fraud is a non-critical edge");
        assertTrue(impact.criticalPath().contains("fraud"));
    }

    @Test
    void anUnknownServiceYieldsAnEmptyImpactRatherThanThrowing() {
        ServiceImpact impact = graph.impactOf("not-a-service");

        assertTrue(impact.dependencies().isEmpty());
        assertTrue(impact.dependents().isEmpty());
    }

    @Test
    void aNullServiceYieldsAnEmptyImpact() {
        assertTrue(graph.impactOf(null).dependencies().isEmpty());
        assertTrue(graph.dependenciesOf(null).isEmpty());
    }

    @Test
    void traversalTerminatesOnACycle() {
        DependencyGraphService cyclicGraph = new DependencyGraphService(cyclic(), 10);

        ServiceImpact impact = cyclicGraph.impactOf("a");

        // b and c are reachable; a is not listed as its own dependency.
        assertEquals(List.of("b", "c"), impact.dependencies());
        assertEquals(List.of("c", "b"), impact.dependents());
    }

    @Test
    void depthLimitTruncatesAndSaysSo() {
        DependencyGraphService shallow = new DependencyGraphService(chain(10), 3);

        ServiceImpact impact = shallow.impactOf("a0");

        assertEquals(List.of("a1", "a2", "a3"), impact.dependencies());
        assertTrue(impact.truncated(), "a partial answer must be flagged as partial");
    }

    @Test
    void explanationNamesOwnerTierDependenciesAndBlastRadius() {
        String text = graph.explainImpact("payments");

        assertTrue(text.contains("platform-reliability"), text);
        assertTrue(text.contains("tier 1"), text);
        assertTrue(text.contains("payments-db"), text);
        assertTrue(text.contains("checkout"), text);
    }

    @Test
    void explanationForALeafSaysTheBlastRadiusIsLimited() {
        assertTrue(graph.explainImpact("checkout").contains("Nothing registered depends on it"));
    }

    @Test
    void explanationDistinguishesUnregisteredFromAbsentService() {
        assertTrue(graph.explainImpact(null).contains("No service was recorded"));
        assertTrue(graph.explainImpact("ghost").contains("not registered in the service catalog"));
    }
}
