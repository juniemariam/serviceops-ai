package com.junie.serviceops.model;

import java.time.Instant;

public record Deployment(
        String id,
        String eventId,
        String service,
        String version,
        String environment,
        String status,
        String deployedBy,
        Instant deployedAt,
        Instant receivedAt) {}
