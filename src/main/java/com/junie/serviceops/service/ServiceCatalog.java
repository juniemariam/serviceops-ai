package com.junie.serviceops.service;

import com.junie.serviceops.model.ServiceEdge;
import com.junie.serviceops.model.ServiceNode;

import java.util.List;

public interface ServiceCatalog {

    /** Returns the service, or null when it is not registered. */
    ServiceNode find(String name);

    List<ServiceNode> all();

    /** Direct edges out of this service: what it depends on. */
    List<ServiceEdge> dependenciesOf(String service);

    /** Direct edges into this service: what depends on it. */
    List<ServiceEdge> dependentsOf(String service);
}
