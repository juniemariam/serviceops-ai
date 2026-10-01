package com.junie.serviceops.model;

import java.time.Instant;

/**
 * A release event from the deployment pipeline, consumed from the deployments topic.
 *
 * @param eventId idempotency key; a redelivery of the same event must not create a second
 *                deployment row
 */
public record DeploymentEvent(
        String eventId,
        String service,
        String version,
        String environment,
        String status,
        String deployedBy,
        Instant deployedAt) {

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
}
