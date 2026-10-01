package com.junie.serviceops.service;

import com.junie.serviceops.model.Deployment;
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
public class JdbcDeploymentStore implements DeploymentStore {

    private static final String COLUMNS =
            "id, event_id, service, version, environment, status, deployed_by, deployed_at, received_at";

    private final JdbcClient jdbc;

    public JdbcDeploymentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Relies on the unique index over {@code event_id} rather than a read-then-write check,
     * so two replicas consuming the same redelivered event cannot both insert.
     */
    @Override
    public boolean saveIfAbsent(Deployment deployment) {
        int inserted = jdbc.sql("""
                        INSERT INTO deployments (%s)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """.formatted(COLUMNS))
                .params(UUID.fromString(deployment.id()), deployment.eventId(), deployment.service(),
                        deployment.version(), deployment.environment(), deployment.status(),
                        deployment.deployedBy(),
                        OffsetDateTime.ofInstant(deployment.deployedAt(), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(deployment.receivedAt(), ZoneOffset.UTC))
                .update();
        return inserted > 0;
    }

    @Override
    public List<Deployment> forServiceBetween(String service, String environment, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT %s FROM deployments
                        WHERE service = ?
                          AND (CAST(? AS text) IS NULL OR environment = ?)
                          AND deployed_at BETWEEN ? AND ?
                        ORDER BY deployed_at DESC
                        """.formatted(COLUMNS))
                .params(service, environment, environment,
                        OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(JdbcDeploymentStore::mapDeployment)
                .list();
    }

    private static Deployment mapDeployment(ResultSet rs, int rowNum) throws SQLException {
        return new Deployment(
                rs.getString("id"),
                rs.getString("event_id"),
                rs.getString("service"),
                rs.getString("version"),
                rs.getString("environment"),
                rs.getString("status"),
                rs.getString("deployed_by"),
                rs.getObject("deployed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("received_at", OffsetDateTime.class).toInstant());
    }
}
