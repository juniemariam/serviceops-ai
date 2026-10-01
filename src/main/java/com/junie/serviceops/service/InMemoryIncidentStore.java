package com.junie.serviceops.service;

import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-durable store used by unit tests and by the {@code memory} profile, which runs the
 * API without a database. Production runs use {@link JdbcIncidentStore}.
 */
@Component
@Profile("memory")
public class InMemoryIncidentStore implements IncidentStore {

    private final Map<String, Incident> incidents = new ConcurrentHashMap<>();

    @Override
    public Incident save(Incident incident) {
        String id = incident.id() == null ? UUID.randomUUID().toString() : incident.id();
        Incident saved = new Incident(id, incident.title(), incident.description(), incident.service(),
                incident.environment(), incident.createdAt(), incident.status(), incident.classification(),
                incident.source(), incident.dedupKey());
        incidents.put(id, saved);
        return saved;
    }

    @Override
    public Incident get(String id) {
        return incidents.get(id);
    }

    @Override
    public Incident update(Incident incident) {
        incidents.put(incident.id(), incident);
        return incident;
    }

    @Override
    public Incident findOpenByDedupKey(String dedupKey) {
        if (dedupKey == null) {
            return null;
        }
        return incidents.values().stream()
                .filter(i -> dedupKey.equals(i.dedupKey()) && i.status() != IncidentStatus.RESOLVED)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Incident> recent(int limit) {
        return incidents.values().stream()
                .sorted(Comparator.comparing(Incident::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }
}
