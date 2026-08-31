# Product Catalog — Technical Case Submission

The **Product Catalog Platform** owns product identity, pricing, and stock data sourced from three independent upstream systems (PIM, Pricing SaaS, Warehouse Management). This repository implements the **product path end-to-end**: a Spring Boot service that upserts and serves product records with a version-guarded idempotent write, and a Go indexer that consumes change events from Kafka and builds search documents. Price and stock paths are **designed but not coded** — see [docs/architecture.md](docs/architecture.md).

## What's here

```
starter-repo/
├── docker-compose.yml                              — Redpanda, Postgres, product-service, indexer wired together
├── README.md
├── docs/
│   └── architecture.md                          — full design: system context, component map, data flows, ADRs
├── indexer/                                        — Go: consumes product.changed, builds search documents
│   ├── Dockerfile
│   ├── go.mod
│   ├── main.go                                     — entry point: Kafka consumer loop
│   └── internal/
│       ├── consumer/
│       │   └── kafka.go                            — Kafka reader, message dispatch
│       └── document/
│           ├── types.go                            — ProductEvent and SearchDocument structs
│           ├── builder.go                          — maps ProductEvent → SearchDocument
│           └── builder_test.go                     — unit tests: field mapping, UTC stamp, excluded fields
└── product-service/                                — Java / Spring Boot: upsert + get product, publishes to Kafka
    ├── Dockerfile
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/example/catalog/
        │   │   ├── ProductServiceApplication.java
        │   │   ├── api/
        │   │   │   ├── ProductController.java       — PUT /products/{id}, GET /products/{id}
        │   │   │   ├── ProductRequest.java          — inbound DTO
        │   │   │   └── ProductResponse.java         — outbound DTO
        │   │   ├── domain/
        │   │   │   ├── Product.java                — JPA entity; carries version-guard fields
        │   │   │   └── ProductStatus.java           — ACTIVE | INACTIVE | DISCONTINUED
        │   │   ├── messaging/
        │   │   │   ├── ProductEvent.java            — Kafka event envelope (eventId, schemaVersion, payload)
        │   │   │   └── ProductEventPublisher.java   — publishes to product.changed topic
        │   │   ├── repository/
        │   │   │   └── ProductRepository.java       — idempotent upsert via ON CONFLICT … WHERE
        │   │   └── service/
        │   │       └── ProductService.java          — upsert logic + sourceUpdatedAt stale-write guard
        │   └── resources/
        │       ├── application.yml
        │       └── db/migration/
        │           └── V1__create_products.sql      — Flyway: products table schema
        └── test/
            └── java/com/example/catalog/
                ├── api/
                │   └── ProductControllerTest.java   — 4 tests: PUT/GET happy path, stale write, 404
                └── service/
                    └── ProductServiceTest.java       — 6 tests: upsert, Kafka publish, stale guard
```

> Full design: [docs/architecture.md](docs/architecture.md) — system context, component map, data flows, ADRs, and what was not built.

## Running it

```bash
docker compose up --build
```

## Running the tests

**Java unit tests** (no Docker required — MockMvc standaloneSetup, no Spring context loaded):

```bash
cd product-service
./mvnw test
```

| Class | Tests | What is covered |
|---|---|---|
| `ProductControllerTest` | 4 | `PUT` returns 200 with full body; stale write still returns 200 (current DB state, not stale payload); `GET` returns 200 when found; `GET` returns 404 when absent |
| `ProductServiceTest` | 6 | Accepted upsert returns product; accepted upsert publishes Kafka event; stale upsert returns current DB state (version-guard invariant — stale writes are silent no-ops that return the live record); stale upsert never publishes event; `findById` returns product when present; `findById` returns empty when absent |

**Go unit tests** (indexer document builder, no Docker required):

```bash
cd indexer
go test ./internal/document/...
```

| Test | What is covered |
|---|---|
| `TestBuild_MapsAllPayloadFields` | Every field from `ProductEvent` payload maps to the correct field in `SearchDocument` |
| `TestBuild_StampsIndexedAtAsUTC` | `IndexedAt` is set at build time and is in UTC (used to measure lag against the 30 s visibility SLA) |
| `TestBuild_ExcludesAuditFields` | `createdAt` and `updatedAt` are deliberately absent from `SearchDocument` — compile-time check via exhaustive struct literal |

`redpanda-setup` exits immediately after enabling topic auto-creation — seeing it as `exited (0)` in `docker compose ps` is expected, not a failure.

| Endpoint | Address |
|---|---|
| product-service REST API | http://localhost:8080 |
| Redpanda Console (topic/message browser) | http://localhost:8085 |
| Kafka (from host) | `localhost:19092` |
| Postgres | `localhost:5432`, user/pass/db: `catalog` |

---

## How I worked with the AI agent

**What was delegated.**
The majority of the code in this repository was generated by GitHub Copilot (Claude Sonnet 4.6) working as a coding agent inside VS Code. I described the architecture decisions and brief constraints in conversation; the agent produced the Spring Boot service, the Go indexer, the Flyway migration, the docker-compose wiring, and the architecture document including the Mermaid diagrams and ADRs.

The VS Code Copilot chat session log is retained in the workspace storage exactly as generated — not tidied. The full conversation history that shaped, constrained, and reviewed the agent's output is at the path recorded in the workspace (`.vscode/` or exported alongside this repo). If copilot instruction files exist in `.github/copilot-instructions.md` they are committed as-is.

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

---

## Exercising it

```bash
# 1. Upsert a product
#    Expect: 200 with full product JSON — id, name="Widget Pro", status="ACTIVE", version=1
curl -X PUT http://localhost:8080/products/00000000-0000-0000-0000-000000000001 \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget Pro","status":"ACTIVE","country":"GB","sourceUpdatedAt":"2026-08-19T10:00:00Z"}'

# 2. Get it back
#    Expect: 200 with the same product JSON
curl http://localhost:8080/products/00000000-0000-0000-0000-000000000001

# 3. Send a stale write (older sourceUpdatedAt)
#    Expect: 200 returning the *current* DB state (name="Widget Pro", version=1) — not the stale payload.
#    No new Kafka message should be produced. No new indexer log line should appear.
curl -X PUT http://localhost:8080/products/00000000-0000-0000-0000-000000000001 \
  -H "Content-Type: application/json" \
  -d '{"name":"SHOULD NOT APPEAR","status":"INACTIVE","country":"GB","sourceUpdatedAt":"2026-08-01T00:00:00Z"}'

# 4. Send a real update (newer sourceUpdatedAt)
#    Expect: 200 with name="Widget Pro v2", version=2
curl -X PUT http://localhost:8080/products/00000000-0000-0000-0000-000000000001 \
  -H "Content-Type: application/json" \
  -d '{"name":"Widget Pro v2","status":"ACTIVE","country":"GB","sourceUpdatedAt":"2026-08-19T12:00:00Z"}'

# 5. Watch the indexer log the search documents
#    Expect: exactly 2 structured JSON log lines — one for step 1, one for step 4
docker compose logs indexer

# 6. Get a product that does not exist
#    Expect: 404
curl http://localhost:8080/products/00000000-0000-0000-0000-000000000099

# Inspect messages in Redpanda Console → http://localhost:8085 → Topics → product.changed
# Exactly 2 messages expected (steps 1 and 4 — the stale write in step 3 produces no event)
```

---

## Assumptions

**Product identity.** The brief says PIM IDs change on re-import, so I generate a stable UUID on our side. In practice a PIM adapter layer would mint this UUID on first sight of a PIM product and persist the mapping. The `PUT /products/{id}` API is called by that adapter, not by the PIM directly.

**One record per product UUID.** The brief mentions "2M SKUs across 8 countries" but doesn't clarify whether the same SKU has country-specific variants (different name, status) or is simply *available* in multiple countries. I built one canonical record per UUID with `country` as a routing/scoping attribute — this keeps the primary key simple and the upsert guard straightforward. If country-specific variants turn out to be required, the natural key becomes `(id, country)` and the schema and upsert logic change significantly. This is the first thing I would validate with the product team (open question #1).

**`sourceUpdatedAt` is provided by the caller.** The upsert guard (`WHERE source_updated_at < EXCLUDED.source_updated_at`) only works if the PIM adapter faithfully forwards the PIM's own modification timestamp. I assumed this is available on the PIM REST API response.

**Kafka is the right transport.** The brief says "event-driven architecture is our default." I took that at face value rather than arguing for HTTP callbacks between product-service and indexer.

**Postgres over in-memory.** The `ON CONFLICT ... WHERE` guard is the interesting behaviour in the upsert — it is invisible with an in-memory map. Postgres was already provided, so I used it.

**No schema registry for now.** The `ProductEvent` envelope carries a `schemaVersion` field so consumers can handle future changes, but there is no Confluent/Apicurio registry enforcing the contract.

---

## What was left out and why

| Item | Reason |
|---|---|
| `price-service` and `stock-service` | Brief explicitly says design only. Both are documented in the architecture with sequence diagrams and idempotency notes. |
| Composite read model (BFF path) | Not in scope per brief. Documented in [docs/architecture.md](docs/architecture.md) §5 and ADR-003 with Redis caching and partial-document handling. |
| Transactional outbox | The correct production pattern for reliable Kafka publishing after a DB commit (ADR-004). Adding Debezium CDC was out of scope; the gap and its consequences are documented. |
| Log compaction config | Topics are auto-created, not explicitly configured as log-compacted. Production would apply this via `rpk topic alter` or Terraform. Documented in [docs/architecture.md](docs/architecture.md) §6. |
| Testcontainers integration tests | Unit tests exist for the controller, service, and Go document builder (see *Running the tests* above). What is missing: (a) concurrent upserts with the same `sourceUpdatedAt` against a real Postgres instance, (b) indexer idempotency on a duplicate Kafka message end-to-end, (c) Flyway migration on a clean schema. Testcontainers would be the right framework for all three. |
| Multi-country product variants | Treated as an open design question rather than a coding decision (see Assumptions). |
| Authentication, pagination, CI/CD, IaC | Out of scope per brief. |

---

## Open questions

1. **Country-specific variants.** Does the same SKU have distinct records per country (different name, status) or is `country` just a market filter? The answer changes the primary key and the event partitioning strategy. The code implements the simpler path (one record per UUID, `country` as an attribute); this is the first thing to validate with the product team.
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
