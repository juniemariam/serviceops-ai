package com.junie.serviceops.model;
import jakarta.validation.constraints.NotBlank;
public record ApprovalRequest(@NotBlank String approver, boolean approved, String comment) {}
