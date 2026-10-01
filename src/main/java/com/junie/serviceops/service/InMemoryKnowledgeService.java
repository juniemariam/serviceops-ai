package com.junie.serviceops.service;

import com.junie.serviceops.model.Evidence;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Keyword-matched corpus used by unit tests and by the {@code memory} profile. It mirrors a
 * subset of the seed rows in V2__seed_knowledge.sql; production retrieval is
 * {@link PgVectorKnowledgeService}.
 */
@Service
@Profile("memory")
public class InMemoryKnowledgeService implements KnowledgeService {

    @Override
    public List<Evidence> retrieve(String query, String service) {
        String text = query.toLowerCase();
        if (text.contains("timeout") || text.contains("latency")) {
            return List.of(
                    new Evidence("runbook-payment-001", "RUNBOOK",
                            "Check database connection pool saturation and compare latency before and after the latest deployment.", 0.96),
                    new Evidence("incident-1842", "INCIDENT",
                            "Payment API timeouts followed release v2.4.1; rollback restored normal p95 latency.", 0.91),
                    new Evidence("service-" + service, "SERVICE_CATALOG",
                            "Owner: platform-reliability. Dependency: payments-db.", 0.74));
        }
        return List.of(new Evidence("runbook-generic-001", "RUNBOOK",
                "Check recent changes, service health, logs, and dependency availability.", 0.62));
    }
}
