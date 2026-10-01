package com.junie.serviceops.service;

import com.junie.serviceops.model.Evidence;

import java.util.List;

public interface KnowledgeService {

    /**
     * Returns grounded evidence for a diagnosis, most relevant first. The owning service's
     * catalog entry is included when one exists, because impact reasoning needs it even
     * when it is not among the closest semantic matches.
     */
    List<Evidence> retrieve(String query, String service);
}
