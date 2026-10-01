package com.junie.serviceops.service;

import com.junie.serviceops.model.Incident;

import java.util.List;

public interface IncidentStore {

    /** Persists a new incident, assigning an id when the request does not carry one. */
    Incident save(Incident incident);

    /** Returns the incident, or null when no incident has that id. */
    Incident get(String id);

    Incident update(Incident incident);

    /** Most recently created incidents first. */
    List<Incident> recent(int limit);

    /**
     * Returns the open incident carrying this alert fingerprint, or null when none is open.
     * Used to collapse a re-firing alert onto the incident it already opened.
     */
    Incident findOpenByDedupKey(String dedupKey);
}
