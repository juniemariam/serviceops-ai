package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;

import java.time.Instant;
import java.util.List;

public interface DeploymentStore {

    /**
     * Stores a deployment unless its event id has already been seen.
     *
     * @return true when the row was inserted, false when it was a duplicate delivery
     */
    boolean saveIfAbsent(Deployment deployment);

    /** Deployments to a service in a time window, most recent first. */
    List<Deployment> forServiceBetween(String service, String environment, Instant from, Instant to);
}
