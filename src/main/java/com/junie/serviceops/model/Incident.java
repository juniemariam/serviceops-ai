package com.junie.serviceops.model;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * @param source    {@link #SOURCE_API} or {@link #SOURCE_ALERT}. Set by the server, never by
 *                  a REST caller, so provenance cannot be spoofed through the API.
 * @param dedupKey  alert fingerprint for ingested incidents, null for API-created ones
 */
public record Incident(String id, @NotBlank String title, @NotBlank String description, String service,
                       String environment, Instant createdAt, IncidentStatus status, Classification classification,
                       String source, String dedupKey) {

    public static final String SOURCE_API = "API";
    public static final String SOURCE_ALERT = "ALERT";

    public Incident withClassification(Classification value) {
        return new Incident(id, title, description, service, environment, createdAt, status, value, source, dedupKey);
    }

    public Incident withStatus(IncidentStatus value) {
        return new Incident(id, title, description, service, environment, createdAt, value, classification, source, dedupKey);
    }
}
