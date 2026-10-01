package com.junie.serviceops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embeds knowledge documents that have no vector yet.
 *
 * <p>Runs on startup because migrations insert text but cannot call the embedding service.
 * It is idempotent — only NULL-embedding rows are touched — so it is safe on every boot and
 * on every replica. A failure here is logged, not fatal: retrieval degrades to keyword
 * search until the classifier is reachable and the next boot completes the backfill.
 */
@Component
@Profile("!memory")
public class KnowledgeIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);
    private static final int BATCH_SIZE = 64;

    private final JdbcClient jdbc;
    private final EmbeddingClient embeddings;

    public KnowledgeIndexer(JdbcClient jdbc, EmbeddingClient embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int embedded = backfill();
            if (embedded > 0) {
                log.info("Embedded {} knowledge documents", embedded);
            }
        } catch (Exception e) {
            log.error("Knowledge embedding backfill failed; retrieval will use keyword search: {}", e.getMessage());
        }
    }

    private int backfill() {
        int total = 0;
        while (true) {
            List<Pending> pending = jdbc.sql("""
                            SELECT source_id, excerpt FROM knowledge_documents
                            WHERE embedding IS NULL ORDER BY source_id LIMIT ?
                            """)
                    .param(BATCH_SIZE)
                    .query((rs, rowNum) -> new Pending(rs.getString("source_id"), rs.getString("excerpt")))
                    .list();
            if (pending.isEmpty()) {
                return total;
            }
            List<float[]> vectors = embeddings.embed(pending.stream().map(Pending::excerpt).toList());
            if (vectors.size() != pending.size()) {
                log.warn("Embedding service returned {} vectors for {} documents; stopping backfill",
                        vectors.size(), pending.size());
                return total;
            }
            for (int i = 0; i < pending.size(); i++) {
                jdbc.sql("UPDATE knowledge_documents SET embedding = CAST(? AS vector) WHERE source_id = ?")
                        .params(EmbeddingClient.toVectorLiteral(vectors.get(i)), pending.get(i).sourceId())
                        .update();
            }
            total += pending.size();
            // A short batch means the pending set is exhausted.
            if (pending.size() < BATCH_SIZE) {
                return total;
            }
        }
    }

    private record Pending(String sourceId, String excerpt) {}
}
