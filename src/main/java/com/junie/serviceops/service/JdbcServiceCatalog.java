package com.junie.serviceops.service;

import com.junie.serviceops.model.ServiceEdge;
import com.junie.serviceops.model.ServiceNode;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
@Profile("!memory")
public class JdbcServiceCatalog implements ServiceCatalog {

    private final JdbcClient jdbc;

    public JdbcServiceCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ServiceNode find(String name) {
        if (name == null) {
            return null;
        }
        return jdbc.sql("SELECT name, owner, tier, telemetry_job, description FROM services WHERE name = ?")
                .param(name)
                .query(JdbcServiceCatalog::mapNode)
                .optional()
                .orElse(null);
    }

    @Override
    public List<ServiceNode> all() {
        return jdbc.sql("SELECT name, owner, tier, telemetry_job, description FROM services ORDER BY name")
                .query(JdbcServiceCatalog::mapNode)
                .list();
    }

    @Override
    public List<ServiceEdge> dependenciesOf(String service) {
        if (service == null) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT from_service, to_service, critical FROM service_dependencies
                        WHERE from_service = ? ORDER BY to_service
                        """)
                .param(service)
                .query(JdbcServiceCatalog::mapEdge)
                .list();
    }

    @Override
    public List<ServiceEdge> dependentsOf(String service) {
        if (service == null) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT from_service, to_service, critical FROM service_dependencies
                        WHERE to_service = ? ORDER BY from_service
                        """)
                .param(service)
                .query(JdbcServiceCatalog::mapEdge)
                .list();
    }

    private static ServiceNode mapNode(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceNode(rs.getString("name"), rs.getString("owner"), rs.getInt("tier"),
                rs.getString("telemetry_job"), rs.getString("description"));
    }

    private static ServiceEdge mapEdge(ResultSet rs, int rowNum) throws SQLException {
        return new ServiceEdge(rs.getString("from_service"), rs.getString("to_service"),
                rs.getBoolean("critical"));
    }
}
