package com.junie.serviceops.model;

/**
 * @param telemetryJob Prometheus {@code job} label, or null when nothing scrapes this
 *                     service. A null is reported as absent telemetry rather than as healthy.
 */
public record ServiceNode(String name, String owner, int tier, String telemetryJob, String description) {}
