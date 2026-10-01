# ServiceOps AI

ServiceOps AI is a Java-based agentic AIOps platform that combines incident-classification models, service-dependency reasoning, grounded retrieval, and human-approved remediation workflows.

## Current MVP

- Java 17 / Spring Boot incident API
- Python TF-IDF + logistic-regression incident classifier
- PostgreSQL persistence for incidents, deployments, and a remediation audit log, with Flyway migrations
- Kafka ingestion of alert and deployment events, with idempotent handling and dead letter topics
- Deployment correlation: a release inside the window becomes ranked evidence and names the root cause
- pgvector cosine retrieval over a seeded corpus of runbooks, incidents, and service catalog entries
- Explicit service-dependency graph reasoning for impact analysis
- Diagnosis workflow with likely root cause and recommended actions
- Approval-gated sandbox remediation endpoint
- OpenTelemetry traces to Jaeger, Prometheus metrics, provisioned Grafana dashboard, and alert rules
- Docker Compose orchestration
- Unit tests for the diagnosis, approval, and audit workflows

The remediation adapter is intentionally sandbox-only. Production actions must be implemented behind authenticated adapters, approval policy, and audit logging.

## Persistence and retrieval

Incidents, remediations, and the knowledge corpus live in PostgreSQL. Flyway applies
`src/main/resources/db/migration` at startup; `V1` creates the schema and the `vector`
extension, and `V2` seeds the corpus.

Retrieval embeds text with the classifier's `/embed` endpoint, a stateless 256-dimension
hashing vectorizer, and ranks documents by cosine distance (`<=>`). Because it is stateless,
every replica produces identical vectors and the corpus never needs re-fitting.

Seeded documents are inserted without embeddings — a migration cannot call the classifier —
and `KnowledgeIndexer` backfills any row with a NULL embedding on startup. The backfill is
idempotent, so it is safe on every boot and on every replica.

When the classifier is unreachable, retrieval degrades to PostgreSQL full-text search rather
than failing the diagnosis. The embedding dimension is fixed in three places that must agree:
`EMBEDDING_DIM` in `ml-service/main.py`, `EmbeddingClient.DIMENSION`, and the `VECTOR(n)`
column in `V1__init.sql`.

### HTTP client to the classifier

`spring.http.client.factory` is pinned to `http-components` (Apache HttpClient 5). This is
load-bearing, not a preference: the JDK `HttpClient` factory frames these POSTs as
`Transfer-Encoding: chunked` in a way uvicorn's HTTP parser rejects, so the classifier
receives an empty body and returns 422. Both clients degrade silently by design, so that
failure shows up only as `java-fallback-v1` classifications and keyword-only retrieval.
Removing `httpclient5` from the POM would reintroduce it.

Connect and read timeouts are set for the same reason — the embedding call sits on the
diagnosis request path, and an unbounded wait there would block a request thread.

## Event ingestion

Two topics are consumed, both auto-created on startup:

| Topic | Payload | Effect |
| --- | --- | --- |
| `serviceops.alerts` | `AlertEvent` | A FIRING alert opens an incident; a RESOLVED alert closes it |
| `serviceops.deployments` | `DeploymentEvent` | Records a release for correlation against incidents |

Kafka delivers at least once, so both handlers are idempotent, each enforced by a database
constraint rather than a read-then-write check:

- **Alerts** de-duplicate on `fingerprint`. A partial unique index allows only one incident
  per fingerprint while it is unresolved, so a flapping alert collapses onto the incident it
  already opened. Once that incident is resolved, a genuine recurrence opens a new one.
- **Deployments** de-duplicate on `eventId` via `ON CONFLICT DO NOTHING`, so a redelivery
  cannot be correlated into a diagnosis twice.

A record that cannot be processed — malformed JSON, or a payload missing required fields —
is routed to `<topic>.DLT` instead of blocking its partition. Payload errors are not retried,
since a redelivery would fail identically; the dead letter record carries the exception type,
message, and original offset as headers. Transient failures retry with a fixed backoff first.

Ingested incidents behave like any other: they classify, diagnose, and require approval
before remediation. Their `source` field is `ALERT` rather than `API`, and the server always
sets it, so a REST caller cannot forge provenance.

### Deployment correlation

A diagnosis looks for releases to the incident's own service within
`serviceops.diagnosis.deployment-correlation-window` (default two hours) before it opened.
When one is found it becomes `DEPLOYMENT` evidence scored by recency, the root cause names
that release, and the rollback action targets its version. When none is found the diagnosis
says so and reports lower confidence rather than asserting a regression it cannot support.

Only the incident's own service is considered. A dependency's release is a weaker signal and
would need its own ranking rather than being mixed in at equal weight.

## Observability

| Service | URL | Notes |
| --- | --- | --- |
| Prometheus | http://localhost:9090 | Scrapes the API and the classifier; alert rules under Alerts |
| Grafana | http://localhost:3000 | Dashboard "ServiceOps AI — Pipeline Health", provisioned, anonymous access for local use |
| Jaeger | http://localhost:16686 | Traces for `serviceops-ai` |

### What the metrics are for

Both external dependencies fail open: an unreachable classifier falls back to a keyword
heuristic, and an unreachable embedding service falls back to full-text retrieval. Both keep
returning 200s, so from the outside a total model outage is indistinguishable from healthy
operation. `serviceops_classifications_total{model_version}` and
`serviceops_retrievals_total{mode}` are what make that visible, and the dashboard's top row is
built around exactly that question.

All tag values are bounded sets. Nothing caller-controlled — no incident id, no payload
service name — is ever used as a label, because unbounded cardinality is what kills a
Prometheus instance.

Meter names are pinned by `ServiceOpsMetricsTest`, which asserts the names **as Prometheus
exports them** rather than the Micrometer ids. The two differ: the client appends `_total` to
counters and strips reserved suffixes. A counter named `serviceops.incidents.created` exports
as `serviceops_incidents_total`, because `_created` is reserved — which is why the meter is
called `incidents.opened` instead.

### Alert design

`ClassifierDown` keys off `up{job="classifier"}`, deliberately independent of API traffic. The
fallback counters can only reveal degradation while requests are flowing, so a classifier that
dies during a quiet period would otherwise stay invisible until the next incident arrived.

`ClassifierDegraded` and `RetrievalDegraded` are ratios of fallback to total rather than
`rate(...) > 0`, so a low-traffic deployment still alerts and a single stale request cannot
hold an alert open indefinitely.

`NoDeploymentCorrelation` is informational and exists to catch a silent producer: if the
deployment topic stops, every diagnosis truthfully reports "no correlated release" while
actually being blind.

Traces exclude `/actuator/**` via an `ObservationPredicate`. A 10-second scrape interval would
otherwise generate thousands of identical single-span traces per day per replica and bury the
ones an investigation needs.

## Run locally

With Docker, which starts PostgreSQL, Kafka, the classifier, the API, Prometheus, Grafana, and Jaeger:

```bash
docker compose up --build
```

With Java 17 and Maven, against a PostgreSQL instance that has pgvector installed:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/serviceops
mvn spring-boot:run
```

To run without any database — in-memory stores and a small keyword-matched corpus, for demos
and smoke tests only — activate the `memory` profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=memory
```

## Demo

Create an incident:

```bash
curl -s -X POST http://localhost:8080/api/v1/incidents \
  -H 'Content-Type: application/json' \
  -d '{"title":"Payment API timeout","description":"p95 latency increased after deployment v2.4.1","service":"payments","environment":"dev"}'
```

Use the returned `id` to diagnose:

```bash
curl -s -X POST http://localhost:8080/api/v1/incidents/{id}/diagnose
```

Approve a recommended action in the sandbox:

```bash
curl -s -X POST http://localhost:8080/api/v1/incidents/{id}/remediations/rollback/approval \
  -H 'Content-Type: application/json' \
  -d '{"approver":"on-call-engineer","approved":true,"comment":"Approved for the development environment"}'
```

Read back the persisted incident, the recent incident list, and the approval audit trail:

```bash
curl -s http://localhost:8080/api/v1/incidents/{id}
curl -s 'http://localhost:8080/api/v1/incidents?limit=20'
curl -s http://localhost:8080/api/v1/incidents/{id}/remediations
curl -s 'http://localhost:8080/api/v1/services/payments/deployments?hours=24'
```

Feed the pipeline instead of using the API directly:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic serviceops.deployments <<'EOF'
{"eventId":"dep-1","service":"payments","version":"v2.4.1","environment":"dev","status":"SUCCEEDED","deployedBy":"ci-bot","deployedAt":"2026-01-01T00:00:00Z"}
EOF

docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic serviceops.alerts <<'EOF'
{"eventId":"alert-1","fingerprint":"payments-p95-high","status":"FIRING","summary":"Payment API timeout","description":"p95 latency above threshold","service":"payments","environment":"dev","severity":"critical","firedAt":"2026-01-01T00:05:00Z"}
EOF
```

## Next production-oriented increments

1. Add a service dependency graph and real telemetry adapters.
2. Add Spring AI tool calling with grounded citations.
3. Build an offline evaluation set for classification, retrieval, root-cause ranking, and remediation success.
