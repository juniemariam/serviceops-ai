package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("memory")
public class InMemoryDeploymentStore implements DeploymentStore {

    private final Map<String, Deployment> byEventId = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(Deployment deployment) {
        return byEventId.putIfAbsent(deployment.eventId(), deployment) == null;
    }

    @Override
    public List<Deployment> forServiceBetween(String service, String environment, Instant from, Instant to) {
        return byEventId.values().stream()
                .filter(d -> Objects.equals(d.service(), service))
                .filter(d -> environment == null || Objects.equals(d.environment(), environment))
                .filter(d -> !d.deployedAt().isBefore(from) && !d.deployedAt().isAfter(to))
                .sorted(Comparator.comparing(Deployment::deployedAt).reversed())
                .toList();
    }
}
