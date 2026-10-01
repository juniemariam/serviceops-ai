package com.junie.serviceops.model;
public record RecommendedAction(String id, String action, String reason, RiskLevel risk, boolean requiresApproval) {}
