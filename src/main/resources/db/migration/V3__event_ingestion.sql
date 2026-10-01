-- Deployments ingested from the deployment topic. They are the strongest correlation
-- signal available for "did a release cause this incident", so they are stored as
-- first-class rows rather than folded into the knowledge corpus.
CREATE TABLE deployments (
    id           UUID PRIMARY KEY,
    event_id     TEXT NOT NULL,
    service      TEXT NOT NULL,
    version      TEXT NOT NULL,
    environment  TEXT NOT NULL,
    status       TEXT NOT NULL,
    deployed_by  TEXT,
    deployed_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL
);

-- Kafka gives at-least-once delivery, so the same event can arrive twice. The producer's
-- event id is the idempotency key: a redelivery collides here instead of creating a
-- duplicate deployment that would then be correlated twice into a diagnosis.
CREATE UNIQUE INDEX idx_deployments_event_id ON deployments (event_id);
CREATE INDEX idx_deployments_service_time ON deployments (service, environment, deployed_at DESC);

-- Where the incident came from: the REST API, or an ingested alert.
ALTER TABLE incidents ADD COLUMN source TEXT NOT NULL DEFAULT 'API';

-- Alert fingerprint, null for API-created incidents.
ALTER TABLE incidents ADD COLUMN dedup_key TEXT;

-- A flapping alert re-fires for as long as the condition holds. This keeps one open
-- incident per fingerprint while allowing a genuine recurrence to open a new incident
-- after the previous one is resolved.
CREATE UNIQUE INDEX idx_incidents_open_dedup_key
    ON incidents (dedup_key)
    WHERE dedup_key IS NOT NULL AND status <> 'RESOLVED';
