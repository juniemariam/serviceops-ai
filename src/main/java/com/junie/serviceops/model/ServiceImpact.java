package com.junie.serviceops.model;

import java.util.List;

/**
 * @param dependencies     what this service needs, transitively
 * @param dependents       what needs this service, transitively — the blast radius
 * @param criticalPath     dependents reachable through critical edges only
 * @param truncated        true when traversal hit the depth limit, so the lists are partial
 */
public record ServiceImpact(
        String service,
        List<String> dependencies,
        List<String> dependents,
        List<String> criticalPath,
        boolean truncated) {}
