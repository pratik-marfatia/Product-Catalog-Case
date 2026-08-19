# Product Catalog — Technical Case Submission

## What's here

```
docker-compose.yml              Redpanda (Kafka), Redpanda Console, Postgres, product-service, indexer
product-service/                Java / Spring Boot — upsert + get product, publishes to Kafka
indexer/                        Go — consumes product.changed, builds and logs search document
docs/architecture.md            Full architecture design: system context, component map, data flows, ADRs
```

## Running it

```bash
docker compose up --build
```

`redpanda-setup` exits immediately after enabling topic auto-creation — seeing it as `exited (0)` in `docker compose ps` is expected, not a failure.

| Endpoint | Address |
|---|---|
| product-service REST API | http://localhost:8080 |
| Redpanda Console (topic/message browser) | http://localhost:8085 |
| Kafka (from host) | `localhost:19092` |
| Postgres | `localhost:5432`, user/pass/db: `catalog` |

## Exercising it

```bash
# Upsert a product
curl -X PUT http://localhost:8080/products/00000000-0000-0000-0000-000000000001 \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget Pro","status":"ACTIVE","country":"GB","sourceUpdatedAt":"2026-08-19T10:00:00Z"}'

# Get it back
curl http://localhost:8080/products/00000000-0000-0000-0000-000000000001

# Send a stale write (older sourceUpdatedAt) — must be silently discarded, no Kafka event
curl -X PUT http://localhost:8080/products/00000000-0000-0000-0000-000000000001 \
  -H "Content-Type: application/json" \
  -d '{"name":"SHOULD NOT APPEAR","status":"INACTIVE","country":"GB","sourceUpdatedAt":"2026-08-01T00:00:00Z"}'

# Send a real update (newer sourceUpdatedAt) — version increments, indexer logs a new document
curl -X PUT http://localhost:8080/products/00000000-0000-0000-0000-000000000001 \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget Pro v2","status":"ACTIVE","country":"GB","sourceUpdatedAt":"2026-08-19T12:00:00Z"}'

# Watch the indexer log the search documents
docker compose logs indexer

# Inspect messages in Redpanda Console → http://localhost:8085 → Topics → product.changed
# Exactly 2 messages expected (steps 1 and 4 above — stale write produces no event)
```

---

## Assumptions

**Product identity.** The brief says PIM IDs change on re-import, so I generate a stable UUID on our side. In practice a PIM adapter layer would mint this UUID on first sight of a PIM product and persist the mapping. The `PUT /products/{id}` API is called by that adapter, not by the PIM directly.

**One record per product UUID.** The brief mentions "2M SKUs across 8 countries" but doesn't clarify whether the same SKU has country-specific variants (different name, status) or is simply *available* in multiple countries. I assumed one canonical record per UUID, with `country` as a routing/scoping attribute. If country-specific variants are needed, the natural key becomes `(id, country)` and the schema and upsert logic change significantly.

**`sourceUpdatedAt` is provided by the caller.** The upsert guard (`WHERE source_updated_at < EXCLUDED.source_updated_at`) only works if the PIM adapter faithfully forwards the PIM's own modification timestamp. I assumed this is available on the PIM REST API response.

**Kafka is the right transport.** The brief says "event-driven architecture is our default." I took that at face value rather than arguing for HTTP callbacks between product-service and indexer.

**Postgres over in-memory.** The `ON CONFLICT ... WHERE` guard is the interesting behaviour in the upsert — it is invisible with an in-memory map. Postgres was already provided, so I used it.

**No schema registry for now.** The `ProductEvent` envelope carries a `schemaVersion` field so consumers can handle future changes, but there is no Confluent/Apicurio registry enforcing the contract.

---

## What was left out and why

| Item | Reason |
|---|---|
| `price-service` and `stock-service` | Brief explicitly says design only. Both are documented in the architecture with sequence diagrams and idempotency notes. |
| Composite read model (BFF path) | Not in scope per brief. Documented in `docs/architecture.md` §5 and ADR-003 with Redis caching and partial-document handling. |
| Transactional outbox | The correct production pattern for reliable Kafka publishing after a DB commit (ADR-004). Adding Debezium CDC was out of scope; the gap and its consequences are documented. |
| Log compaction config | Topics are auto-created, not explicitly configured as log-compacted. Production would apply this via `rpk topic alter` or Terraform. Documented in architecture §6. |
| Tests | Skipped to stay inside the time budget. The interesting cases are: (a) concurrent upserts with the same `sourceUpdatedAt`, (b) stale write rejection, (c) indexer idempotency on duplicate Kafka message. Testcontainers would be the right framework. |
| Multi-country product variants | Treated as an open design question rather than a coding decision (see Assumptions). |
| Authentication, pagination, CI/CD, IaC | Out of scope per brief. |

---

## Open questions

1. **Country-specific variants.** Does the same SKU have distinct records per country (different name, status) or is `country` just a market filter? The answer changes the primary key and the event partitioning strategy.
2. **Who mints the stable UUID?** The PIM adapter, our service on first POST, or a shared ID service? This affects whether `PUT /products/{id}` is the right API shape.
3. **Schema evolution.** When `ProductEvent` payload shape changes, how do unupdated consumers handle it? `schemaVersion` buys time, but we need a registry and a compatibility policy (`BACKWARD`, `FORWARD`).
4. **Exactly-once indexing.** The indexer is idempotent (replaying the same event produces the same document), but Kafka at-least-once means the search engine sees duplicate index writes. Acceptable if the search engine deduplicates by `version`; worth confirming.
5. **PIM polling vs. change events.** The architecture assumes the PIM emits change events. If it only exposes REST, we need a polling adapter with cursor-based pagination and delta detection.

---

## What I would do next with another week

1. **Transactional outbox** — replace the direct post-commit Kafka publish with Debezium CDC reading the Postgres WAL. Gives exactly-once DB write + at-least-once Kafka delivery with no silent loss on crash.
2. **Log compaction** — explicitly configure `product.changed` as log-compacted with infinite retention. Add a rebuild script that resets the indexer consumer group to `earliest` against a new index alias and swaps on catch-up.
3. **`price-service`** — webhook receiver with HMAC signature verification, idempotent write (deduplicate by `priceId + validFrom`), dead-letter reconciliation for missed webhooks.
4. **`stock-service`** — consume the WMS Kafka topic, handle at-least-once and unordered delivery via `sequenceNo` guard (same pattern as `sourceUpdatedAt` on products).
5. **Composite read model** — subscribe to all three domain topics, assemble `{product, price, stock}`, write to Redis with per-field TTLs. Handle partial documents gracefully.
6. **Tests** — Testcontainers integration tests: stale write rejection, concurrent upserts, indexer duplicate-message idempotency, Flyway migration on clean schema.
7. **Schema registry** — Apicurio or Confluent with `BACKWARD` compatibility enforced on `product.changed`.

---

## How I worked with the AI agent

**What was delegated.**
The majority of the code in this repository was generated by GitHub Copilot (Claude Sonnet 4.6) working as a coding agent inside VS Code. I described the architecture decisions and brief constraints in conversation; the agent produced the Spring Boot service, the Go indexer, the Flyway migration, the docker-compose wiring, and the architecture document including the Mermaid diagrams and ADRs.

**What I wrote or intervened on.**
Nothing was merged without being read line by line. Specific interventions:
- Challenged the agent's initial inclusion of `color`, `brand`, `price`, and `user-type` as product control fields. These are descriptive attributes, not control data — an important distinction the brief is testing. The agent revised after the challenge.
- Pushed back on `Integer/Long` as the ID type and confirmed UUID is the correct choice for an idempotent `PUT /products/{id}` API where the caller supplies the ID.
- Added the `composite-read-model` participant to sequence diagram 4a, which the agent had initially omitted.
- Verified the `@Modifying(clearAutomatically = true)` reasoning — the agent added it correctly; I confirmed the JPA L1 cache invalidation logic was sound before accepting it.

**Where the agent got it wrong.**
- Specified `kafka-go v0.4.47` in `go.mod` — this version tag does not exist on the module. Caught during `docker compose up --build` (exit code 1 on `go mod download`). Fixed by changing to `v0.4.43` and switching the Dockerfile to `go mod tidy`.
- The null-safety warning fix went through an unnecessary round of changes (adding `@NonNull` to individual parameters across multiple classes) before settling on the correct class-level `@SuppressWarnings("null")` — added noise that required review.

**How I satisfied myself the output met a standard I would sign my name to.**
- Read every file before approving it. The architecture document went through two revision rounds (missing composite-read-model in sequence diagram 4a; missing overview paragraph for the component map section).
- Ran `docker compose up --build` end-to-end and exercised all smoke test cases: upsert, read, stale write rejection, real update, 404, and indexer log output.
- Inspected the Kafka messages in Redpanda Console to confirm exactly 2 messages were produced — confirming the stale write correctly produced no event.
