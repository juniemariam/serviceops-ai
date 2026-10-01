-- Replaces the hardcoded three-entry map in DependencyGraphService.
CREATE TABLE services (
    name              TEXT PRIMARY KEY,
    owner             TEXT NOT NULL,
    tier              INTEGER NOT NULL CHECK (tier BETWEEN 1 AND 3),
    -- Prometheus `job` label for this service, or NULL when nothing scrapes it.
    -- A NULL means the telemetry adapter reports "no telemetry configured" rather
    -- than inventing numbers for a service it cannot actually observe.
    telemetry_job     TEXT,
    description       TEXT
);

CREATE TABLE service_dependencies (
    from_service TEXT NOT NULL REFERENCES services (name) ON DELETE CASCADE,
    to_service   TEXT NOT NULL REFERENCES services (name) ON DELETE CASCADE,
    -- A critical edge means the caller cannot function without the callee.
    critical     BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (from_service, to_service),
    -- Self-edges would make traversal depth meaningless; cycles between distinct
    -- services are permitted because real topologies have them, and the traversal
    -- is written to tolerate them.
    CHECK (from_service <> to_service)
);

CREATE INDEX idx_service_dependencies_to ON service_dependencies (to_service);

-- The business services carried over from the hardcoded map and the seeded catalog.
INSERT INTO services (name, owner, tier, telemetry_job, description) VALUES
    ('checkout',      'platform-reliability', 1, NULL, 'Customer-facing checkout flow'),
    ('payments',      'platform-reliability', 1, NULL, 'Payment authorisation and capture'),
    ('fraud',         'risk-engineering',     2, NULL, 'Transaction risk scoring'),
    ('identity',      'identity-platform',    1, NULL, 'Authentication and token issuance'),
    ('checkout-db',   'platform-reliability', 1, NULL, 'Checkout datastore'),
    ('payments-db',   'platform-reliability', 1, NULL, 'Payments datastore'),
    ('fraud-db',      'risk-engineering',     2, NULL, 'Fraud datastore'),
    ('identity-db',   'identity-platform',    1, NULL, 'Identity datastore');

-- ServiceOps' own components, which are genuinely scraped. These give the telemetry
-- adapter something real to read, and let the platform diagnose itself.
INSERT INTO services (name, owner, tier, telemetry_job, description) VALUES
    ('serviceops-api',        'platform-reliability', 1, 'serviceops-api', 'Incident API and diagnosis engine'),
    ('serviceops-classifier', 'platform-reliability', 2, 'classifier',     'Incident classification and embedding model service'),
    ('serviceops-postgres',   'platform-reliability', 1, NULL,             'Incident, deployment, and knowledge store'),
    ('serviceops-kafka',      'platform-reliability', 1, NULL,             'Alert and deployment event transport');

INSERT INTO service_dependencies (from_service, to_service, critical) VALUES
    ('checkout', 'payments',    TRUE),
    ('checkout', 'fraud',       FALSE),
    ('checkout', 'checkout-db', TRUE),
    ('payments', 'payments-db', TRUE),
    ('payments', 'identity',    TRUE),
    ('fraud',    'fraud-db',    TRUE),
    ('identity', 'identity-db', TRUE),
    ('serviceops-api', 'serviceops-classifier', FALSE),
    ('serviceops-api', 'serviceops-postgres',   TRUE),
    ('serviceops-api', 'serviceops-kafka',      FALSE);
