package com.junie.serviceops.service;

import com.junie.serviceops.model.Remediation;

import java.util.List;

/**
 * Audit log of approval decisions and sandbox executions. Rejections are recorded too:
 * the fact that someone declined an action is as auditable as the fact that someone ran it.
 */
public interface RemediationStore {

    Remediation save(Remediation remediation);

    /** Most recent decision first. */
    List<Remediation> forIncident(String incidentId);
}
