package com.junie.serviceops.service;

import com.junie.serviceops.model.Remediation;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!memory")
public class JdbcRemediationStore implements RemediationStore {

    private final JdbcClient jdbc;

    public JdbcRemediationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Remediation save(Remediation remediation) {
        Instant executedAt = remediation.executedAt() == null ? Instant.now() : remediation.executedAt();
        jdbc.sql("""
                        INSERT INTO remediations (id, incident_id, action, status, approved_by, executed_at, message)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.fromString(remediation.id()), UUID.fromString(remediation.incidentId()),
                        remediation.action(), remediation.status(), remediation.approvedBy(),
                        OffsetDateTime.ofInstant(executedAt, ZoneOffset.UTC), remediation.message())
                .update();
        return remediation;
    }

    @Override
    public List<Remediation> forIncident(String incidentId) {
        UUID key;
        try {
            key = UUID.fromString(incidentId);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, incident_id, action, status, approved_by, executed_at, message
                        FROM remediations WHERE incident_id = ? ORDER BY executed_at DESC
                        """)
                .param(key)
                .query(JdbcRemediationStore::mapRemediation)
                .list();
    }

    private static Remediation mapRemediation(ResultSet rs, int rowNum) throws SQLException {
        return new Remediation(
                rs.getString("id"),
                rs.getString("incident_id"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("approved_by"),
                rs.getObject("executed_at", OffsetDateTime.class).toInstant(),
                rs.getString("message"));
    }
}
