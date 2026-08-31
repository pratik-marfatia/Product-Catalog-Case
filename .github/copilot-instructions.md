# GitHub Copilot — Project Instructions

## What this project is

An event-driven Product Catalog domain. It owns product identity, pricing, and stock data from three independent upstream systems and serves two consumers: a latency-sensitive storefront BFF and a search indexer. The implemented slice is `product-service` (Java/Spring Boot) + `search-indexer` (Go) communicating over Kafka (Redpanda).

---

## Architecture rules — always enforce these

### Domain boundaries
- `product-service` owns **product identity only** — `id`, `name`, `status`, `country`, and control fields. It does not own price or stock.
- Never add `price`, `rating`, `color`, `brand`, or any descriptive attribute to the product model. These are separate domains with their own upstream systems and event streams.
- Never add a field to `Product.java` without a justification in `docs/architecture.md §3`.

### Version guard / idempotency
- Every upsert to the products table must use the `sourceUpdatedAt` guard: `ON CONFLICT DO UPDATE ... WHERE source_updated_at < EXCLUDED.source_updated_at`.
- A stale write (older `sourceUpdatedAt`) must be a silent no-op — it returns the current DB state with HTTP 200, not an error and not the stale payload.
- Kafka events are published **only** when the DB row was actually changed. A stale write must produce no Kafka message.
- All Kafka consumers must be idempotent: processing the same message twice must produce the same outcome as processing it once.

### Event envelope
Every event published to Kafka must include: `eventId` (UUID), `eventType` (string), `occurredAt` (ISO-8601 UTC), `schemaVersion` (integer), and `payload` (domain object). Do not publish raw JPA entities.

### Identity
Always use `UUID` for entity identities. Never use `Long` or `Integer` as a public-facing ID.

---

## Java / Spring Boot conventions

- Use `@Modifying(clearAutomatically = true)` on any JPQL `UPDATE` or `DELETE` query to prevent stale L1 cache reads after the write.
- Separate layers strictly: `api/` (DTOs + controller), `domain/` (JPA entity), `service/` (business logic), `repository/` (data access), `messaging/` (Kafka). Do not cross these boundaries.
- DTOs (`ProductRequest`, `ProductResponse`) must not leak JPA entity internals. Map explicitly in the service or controller.
- Use `Instant` (not `LocalDateTime`, not `Date`) for all timestamps. Store and transmit in UTC.
- Application config belongs in `application.yml`. Do not hardcode Kafka topic names, host names, or ports in Java source.
- Do not add Spring Security, pagination, or HATEOAS — explicitly out of scope.

---

## Go conventions

- Keep business logic in `internal/` packages. `main.go` is wiring only.
- Use structured logging (`log/slog` or `zerolog`) — never `fmt.Println` for production log lines.
- Use exhaustive struct literals in tests so that adding a field to a struct causes a compile error in the test: this is the intended compile-time safety check.
- `IndexedAt` on `SearchDocument` must always be set at build time and must be UTC — it is used to measure lag against the 30-second visibility SLA.
- `createdAt` and `updatedAt` from the product event are deliberately **excluded** from `SearchDocument`. Do not add them back.

---

## Security guard-rails — always enforce these

- **No hardcoded credentials.** DB passwords, Kafka credentials, and API keys must come from environment variables. The `catalog`/`catalog` Postgres defaults in `docker-compose.yml` are local-dev only — never replicate them in Java or Go source.
- **Validate all external input.** Every inbound DTO (`@RequestBody` record) must have Bean Validation constraints on every field, and the controller parameter must be annotated `@Valid`. See [security.instructions.md](instructions/security.instructions.md).
- **No exception details in HTTP responses.** Map validation failures and unexpected errors via `@ControllerAdvice` to generic error bodies. Never pass `e.getMessage()` into a response body.
- **No sensitive data in logs.** Log only IDs, offsets, schema versions, and system state — never request body contents or field values sourced from external systems.
- **Schema version guard in all Kafka consumers.** Any code that consumes Kafka messages must check `schemaVersion` before processing and skip (with a structured log line) any unsupported version.
- **Parameterised queries only.** Never build SQL by string concatenation. Use JPA named parameters (`@Param`) for all native queries.

---

## Testing rules

### Java
- Unit tests must NOT load the Spring application context. Use `MockMvc` `standaloneSetup` for controller tests.
- Every test class for `ProductServiceTest` must cover: (a) accepted upsert returns product, (b) accepted upsert publishes Kafka event, (c) stale upsert returns current DB state, (d) stale upsert does NOT publish event.
- Do not use `@SpringBootTest` for unit tests — it is for integration tests only (and those require Testcontainers, which is not yet set up).

### Go
- Document builder tests must use exhaustive struct literals — this is a compile-time guard, not just a style choice.
- Do not mock the Kafka consumer in unit tests. Test the document builder (`internal/document`) in isolation.

---

## What NOT to build (unless explicitly asked)

| Item | Why |
|---|---|
| `price-service` | Design only — documented in architecture §4b |
| `stock-service` | Design only — documented in architecture §4c |
| Composite read model | Design only — documented in architecture §5 |
| Transactional outbox | Out of scope for this exercise — gap is documented in ADR-004 |
| Real search engine (Elasticsearch / OpenSearch) | The indexer logs the document; no real index |
| Authentication / authorisation | Explicitly out of scope per brief |
| Pagination | Explicitly out of scope per brief |
| CI/CD pipelines or IaC | Explicitly out of scope per brief |
| Schema registry | Documented as a "what I'd do next" item |

---

## Security baseline

- No secrets, passwords, or API keys in source files or `application.yml`. Use environment variables (already wired via `docker-compose.yml`).
- No PII in Kafka event payloads or structured log lines.
- Validate all inbound JSON at the API boundary (`ProductRequest`) before passing to the service layer.
- Do not log full request bodies at INFO level — they may contain sensitive data.
