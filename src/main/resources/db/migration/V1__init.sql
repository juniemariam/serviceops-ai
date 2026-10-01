CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE incidents (
    id                              UUID PRIMARY KEY,
    title                           TEXT NOT NULL,
    description                     TEXT NOT NULL,
    service                         TEXT,
    environment                     TEXT NOT NULL,
    created_at                      TIMESTAMPTZ NOT NULL,
    status                          TEXT NOT NULL,
    classification_category         TEXT,
    classification_priority         TEXT,
    classification_assignment_group TEXT,
    classification_confidence       DOUBLE PRECISION,
    classification_model_version    TEXT
);

CREATE INDEX idx_incidents_service ON incidents (service);
CREATE INDEX idx_incidents_status ON incidents (status);
CREATE INDEX idx_incidents_created_at ON incidents (created_at DESC);

CREATE TABLE remediations (
    id          UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    action      TEXT NOT NULL,
    status      TEXT NOT NULL,
    approved_by TEXT,
    executed_at TIMESTAMPTZ NOT NULL,
    message     TEXT
);

CREATE INDEX idx_remediations_incident ON remediations (incident_id, executed_at DESC);

-- Embedding dimension is fixed by the classifier's HashingVectorizer feature count.
-- Changing it requires a new migration that rewrites the column and re-embeds every row.
CREATE TABLE knowledge_documents (
    source_id   TEXT PRIMARY KEY,
    source_type TEXT NOT NULL,
    service     TEXT,
    excerpt     TEXT NOT NULL,
    embedding   VECTOR(256)
);

CREATE INDEX idx_knowledge_service ON knowledge_documents (service);

-- Supports the keyword fallback used when the embedding service is unreachable. The
-- two-argument to_tsvector is immutable, so it is indexable; the one-argument form is not.
CREATE INDEX idx_knowledge_excerpt_fts ON knowledge_documents USING GIN (to_tsvector('english', excerpt));

-- An ivfflat index needs representative data to build useful lists, so it is created
-- in a later migration once the corpus is large enough. Sequential cosine scans are
-- fine at seed-corpus scale.
