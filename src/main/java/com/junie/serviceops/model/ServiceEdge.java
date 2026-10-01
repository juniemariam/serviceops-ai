package com.junie.serviceops.model;

/** @param critical the caller cannot function without the callee */
public record ServiceEdge(String fromService, String toService, boolean critical) {}
