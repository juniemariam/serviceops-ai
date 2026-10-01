package com.junie.serviceops.service;

import com.junie.serviceops.model.Evidence;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cosine-similarity retrieval over {@code knowledge_documents}.
 *
 * <p>Falls back to full-text keyword search when the embedding service is unavailable or
 * when the corpus has not been embedded yet, so a diagnosis still produces grounded
 * evidence rather than nothing at all.
 */
@Service
@Profile("!memory")
public class PgVectorKnowledgeService implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKnowledgeService.class);

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;
    private final ServiceOpsMetrics metrics;
    private final int topK;

    public PgVectorKnowledgeService(JdbcClient jdbc, EmbeddingClient embeddings, ServiceOpsMetrics metrics,
                                    @Value("${serviceops.retrieval.top-k:3}") int topK) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.metrics = metrics;
        this.topK = topK;
    }

    @Override
    public List<Evidence> retrieve(String query, String service) {
        float[] queryVector = embeddings.embedOne(query);
        String mode = queryVector == null ? ServiceOpsMetrics.MODE_KEYWORD : ServiceOpsMetrics.MODE_VECTOR;
        List<Evidence> matches = queryVector == null ? keywordSearch(query) : vectorSearch(queryVector);
        if (matches.isEmpty() && queryVector != null) {
            // An embedded query that matched nothing still fell back to keyword search.
            mode = ServiceOpsMetrics.MODE_KEYWORD;
            matches = keywordSearch(query);
        }
        metrics.retrieved(mode, matches.size());
        return withServiceCatalog(matches, service);
    }

    private List<Evidence> vectorSearch(float[] queryVector) {
        String literal = EmbeddingClient.toVectorLiteral(queryVector);
        return jdbc.sql("""
                        SELECT source_id, source_type, excerpt, 1 - (embedding <=> CAST(? AS vector)) AS relevance
                        FROM knowledge_documents
                        WHERE embedding IS NOT NULL
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT ?
                        """)
                .params(literal, literal, topK)
                .query(PgVectorKnowledgeService::mapEvidence)
                .list();
    }

    /**
     * Ranks by {@code ts_rank} over the excerpt.
     *
     * <p>The parsed tsquery's {@code &} operators are rewritten to {@code |}. Incident text is
     * a title plus a description, so requiring every term — which is what
     * {@code websearch_to_tsquery} produces — matches nothing in a runbook-sized excerpt.
     * Rewriting the already-parsed query text keeps phrase ({@code <->}) and negation
     * operators intact and never interpolates caller input into SQL.
     *
     * <p>Relevance is reported on the same 0-1 scale as cosine similarity, but the two are not
     * directly comparable; callers should not mix ranks from both paths in one list.
     */
    private List<Evidence> keywordSearch(String query) {
        try {
            return jdbc.sql("""
                            WITH q AS (
                                SELECT NULLIF(replace(websearch_to_tsquery('english', ?)::text, '&', '|'), '')::tsquery AS tsq
                            )
                            SELECT source_id, source_type, excerpt,
                                   ts_rank(to_tsvector('english', excerpt), q.tsq) AS relevance
                            FROM knowledge_documents, q
                            WHERE q.tsq IS NOT NULL
                              AND to_tsvector('english', excerpt) @@ q.tsq
                            ORDER BY relevance DESC
                            LIMIT ?
                            """)
                    .params(query, topK)
                    .query(PgVectorKnowledgeService::mapEvidence)
                    .list();
        } catch (Exception e) {
            log.warn("Keyword retrieval failed for query of length {}: {}", query.length(), e.getMessage());
            return List.of();
        }
    }

    /** Adds the owning service's catalog entry if retrieval did not already surface it. */
    private List<Evidence> withServiceCatalog(List<Evidence> matches, String service) {
        Map<String, Evidence> deduplicated = new LinkedHashMap<>();
        for (Evidence evidence : matches) {
            deduplicated.putIfAbsent(evidence.sourceId(), evidence);
        }
        if (service != null && deduplicated.values().stream().noneMatch(e -> "SERVICE_CATALOG".equals(e.sourceType()))) {
            jdbc.sql("""
                            SELECT source_id, source_type, excerpt, 0.74 AS relevance
                            FROM knowledge_documents
                            WHERE service = ? AND source_type = 'SERVICE_CATALOG'
                            LIMIT 1
                            """)
                    .param(service)
                    .query(PgVectorKnowledgeService::mapEvidence)
                    .optional()
                    .ifPresent(evidence -> deduplicated.putIfAbsent(evidence.sourceId(), evidence));
        }
        return new ArrayList<>(deduplicated.values());
    }

    private static Evidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new Evidence(
                rs.getString("source_id"),
                rs.getString("source_type"),
                rs.getString("excerpt"),
                rs.getDouble("relevance"));
    }
}
