package com.junie.serviceops.service;

import com.junie.serviceops.model.ServiceEdge;
import com.junie.serviceops.model.ServiceNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mirrors a subset of the V4 seed rows for tests and the {@code memory} profile. Kept
 * deliberately small: the authoritative graph is the database, and duplicating all of it
 * here would just be a second copy to drift.
 */
@Component
@Profile("memory")
public class InMemoryServiceCatalog implements ServiceCatalog {

    private final Map<String, ServiceNode> nodes = Map.of(
            "checkout", new ServiceNode("checkout", "platform-reliability", 1, null, "Checkout flow"),
            "payments", new ServiceNode("payments", "platform-reliability", 1, null, "Payments"),
            "fraud", new ServiceNode("fraud", "risk-engineering", 2, null, "Risk scoring"),
            "identity", new ServiceNode("identity", "identity-platform", 1, null, "Authentication"),
            "checkout-db", new ServiceNode("checkout-db", "platform-reliability", 1, null, "Checkout store"),
            "payments-db", new ServiceNode("payments-db", "platform-reliability", 1, null, "Payments store"),
            "fraud-db", new ServiceNode("fraud-db", "risk-engineering", 2, null, "Fraud store"),
            "identity-db", new ServiceNode("identity-db", "identity-platform", 1, null, "Identity store"));

    private final List<ServiceEdge> edges = List.of(
            new ServiceEdge("checkout", "payments", true),
            new ServiceEdge("checkout", "fraud", false),
            new ServiceEdge("checkout", "checkout-db", true),
            new ServiceEdge("payments", "payments-db", true),
            new ServiceEdge("payments", "identity", true),
            new ServiceEdge("fraud", "fraud-db", true),
            new ServiceEdge("identity", "identity-db", true));

    @Override
    public ServiceNode find(String name) {
        return name == null ? null : nodes.get(name);
    }

    @Override
    public List<ServiceNode> all() {
        return nodes.values().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }

    @Override
    public List<ServiceEdge> dependenciesOf(String service) {
        return edges.stream().filter(e -> Objects.equals(e.fromService(), service)).toList();
    }

    @Override
    public List<ServiceEdge> dependentsOf(String service) {
        return edges.stream().filter(e -> Objects.equals(e.toService(), service)).toList();
    }
}
