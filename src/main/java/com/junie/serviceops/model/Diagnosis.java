package com.junie.serviceops.model;
import java.time.Instant; import java.util.List;
public record Diagnosis(String incidentId, String summary, String likelyRootCause, List<Evidence> evidence, List<RecommendedAction> recommendedActions, double confidence, Instant generatedAt) {}
