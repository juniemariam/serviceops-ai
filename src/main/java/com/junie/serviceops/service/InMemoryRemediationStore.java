package com.junie.serviceops.service;

import com.junie.serviceops.model.Remediation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Profile("memory")
public class InMemoryRemediationStore implements RemediationStore {

    private final Map<String, List<Remediation>> byIncident = new ConcurrentHashMap<>();

    @Override
    public Remediation save(Remediation remediation) {
        byIncident.computeIfAbsent(remediation.incidentId(), key -> new CopyOnWriteArrayList<>()).add(remediation);
        return remediation;
    }

    @Override
    public List<Remediation> forIncident(String incidentId) {
        return byIncident.getOrDefault(incidentId, List.of()).stream()
                .sorted(Comparator.comparing(Remediation::executedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
}
