package com.junie.serviceops.service;

import com.junie.serviceops.model.Classification;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.model.IncidentStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!memory")
public class JdbcIncidentStore implements IncidentStore {

    private static final String COLUMNS = """
            id, title, description, service, environment, created_at, status,
            classification_category, classification_priority, classification_assignment_group,
            classification_confidence, classification_model_version, source, dedup_key
            """;

    private final JdbcClient jdbc;

    public JdbcIncidentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Incident save(Incident incident) {
        UUID id = incident.id() == null ? UUID.randomUUID() : UUID.fromString(incident.id());
        Instant createdAt = incident.createdAt() == null ? Instant.now() : incident.createdAt();
        IncidentStatus status = incident.status() == null ? IncidentStatus.OPEN : incident.status();
        Classification classification = incident.classification();
        jdbc.sql("""
                        INSERT INTO incidents (%s)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.formatted(COLUMNS))
                .params(id, incident.title(), incident.description(), incident.service(), incident.environment(),
                        OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC), status.name(),
                        classification == null ? null : classification.category(),
                        classification == null ? null : classification.priority(),
                        classification == null ? null : classification.assignmentGroup(),
                        classification == null ? null : classification.confidence(),
                        classification == null ? null : classification.modelVersion(),
                        incident.source() == null ? Incident.SOURCE_API : incident.source(),
                        incident.dedupKey())
                .update();
        return new Incident(id.toString(), incident.title(), incident.description(), incident.service(),
                incident.environment(), createdAt, status, classification,
                incident.source() == null ? Incident.SOURCE_API : incident.source(), incident.dedupKey());
    }

    @Override
    public Incident get(String id) {
        UUID key = parseId(id);
        if (key == null) {
            return null;
        }
        return jdbc.sql("SELECT %s FROM incidents WHERE id = ?".formatted(COLUMNS))
                .param(key)
                .query(JdbcIncidentStore::mapIncident)
                .optional()
                .orElse(null);
    }

    @Override
    public Incident update(Incident incident) {
        Classification classification = incident.classification();
        int updated = jdbc.sql("""
                        UPDATE incidents SET
                            title = ?, description = ?, service = ?, environment = ?, status = ?,
                            classification_category = ?, classification_priority = ?,
                            classification_assignment_group = ?, classification_confidence = ?,
                            classification_model_version = ?
                        WHERE id = ?
                        """)
                .params(incident.title(), incident.description(), incident.service(), incident.environment(),
                        incident.status() == null ? IncidentStatus.OPEN.name() : incident.status().name(),
                        classification == null ? null : classification.category(),
                        classification == null ? null : classification.priority(),
                        classification == null ? null : classification.assignmentGroup(),
                        classification == null ? null : classification.confidence(),
                        classification == null ? null : classification.modelVersion(),
                        UUID.fromString(incident.id()))
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Incident not found: " + incident.id());
        }
        return incident;
    }

    @Override
    public Incident findOpenByDedupKey(String dedupKey) {
        if (dedupKey == null) {
            return null;
        }
        return jdbc.sql("""
                        SELECT %s FROM incidents
                        WHERE dedup_key = ? AND status <> 'RESOLVED'
                        ORDER BY created_at DESC LIMIT 1
                        """.formatted(COLUMNS))
                .param(dedupKey)
                .query(JdbcIncidentStore::mapIncident)
                .optional()
                .orElse(null);
    }

    @Override
    public List<Incident> recent(int limit) {
        return jdbc.sql("SELECT %s FROM incidents ORDER BY created_at DESC LIMIT ?".formatted(COLUMNS))
                .param(limit)
                .query(JdbcIncidentStore::mapIncident)
                .list();
    }

    /** Incident ids are UUIDs; a malformed id is a miss, not a server error. */
    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Incident mapIncident(ResultSet rs, int rowNum) throws SQLException {
        String category = rs.getString("classification_category");
        Classification classification = category == null ? null : new Classification(
                category,
                rs.getString("classification_priority"),
                rs.getString("classification_assignment_group"),
                rs.getDouble("classification_confidence"),
                rs.getString("classification_model_version"));
        return new Incident(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("service"),
                rs.getString("environment"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                IncidentStatus.valueOf(rs.getString("status")),
                classification,
                rs.getString("source"),
                rs.getString("dedup_key"));
    }
}
