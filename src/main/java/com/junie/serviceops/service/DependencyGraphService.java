package com.junie.serviceops.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class DependencyGraphService {
    private final Map<String, List<String>> dependencies = Map.of(
            "checkout", List.of("payments", "fraud", "checkout-db"),
            "payments", List.of("payments-db", "identity"),
            "fraud", List.of("fraud-db"));

    /**
     * The backing map is immutable, and immutable maps throw on a null key rather than
     * missing, so an incident with no service must be filtered out before the lookup.
     */
    public List<String> dependenciesOf(String service) {
        return service == null ? List.of() : dependencies.getOrDefault(service, List.of());
    }

    public String explainImpact(String service) {
        if (service == null) {
            return "No service was recorded for this incident, so no dependency impact could be derived.";
        }
        List<String> downstream = dependenciesOf(service);
        return downstream.isEmpty()
                ? "No registered dependencies for " + service + "."
                : service + " depends on " + String.join(", ", downstream) + ".";
    }
}
