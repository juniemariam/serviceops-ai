package com.junie.serviceops.model;
import java.time.Instant;
public record Remediation(String id, String incidentId, String action, String status, String approvedBy, Instant executedAt, String message) {}
