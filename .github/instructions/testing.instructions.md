---
applyTo: "**/*Test.java,**/*_test.go"
---

# Testing Guidelines

## Java unit tests

### What must be tested in `ProductServiceTest`

Every test run must cover all six of these behaviours. Do not remove or merge them:

| # | Scenario | What to assert |
|---|---|---|
| 1 | Accepted upsert | Returns the saved `Product` |
| 2 | Accepted upsert | Publishes exactly one Kafka event with the correct payload |
| 3 | Stale upsert (`sourceUpdatedAt` ≤ current) | Returns the **current DB state**, not the stale payload |
| 4 | Stale upsert | Publishes **zero** Kafka events |
| 5 | `findById` — found | Returns `Optional` containing the product |
| 6 | `findById` — not found | Returns `Optional.empty()` |

### What must be tested in `ProductControllerTest`

| # | Scenario | What to assert |
|---|---|---|
| 1 | `PUT` — accepted | HTTP 200, response body contains all product fields |
| 2 | `PUT` — stale | HTTP 200, response body reflects **current DB state** |
| 3 | `GET` — found | HTTP 200 with product JSON |
| 4 | `GET` — not found | HTTP 404 |

### Setup rules

- Use `MockMvc` with `standaloneSetup`. Never load the full Spring context in unit tests.
- Mock `ProductService` using Mockito. Do not use real Kafka or real Postgres in unit tests.
- Never annotate a unit test class with `@SpringBootTest`.

### What Testcontainers integration tests would add (not yet implemented)

The following scenarios require a real Postgres and are the highest-value gaps:
1. Concurrent upserts with identical `sourceUpdatedAt` — only one should win.
2. Replaying the same Kafka message to the indexer twice — must produce identical output.
3. Flyway migration against a clean schema — confirms `V1__create_products.sql` is valid.

---

## Go unit tests

### What must be tested in `builder_test.go`

| Test | What it proves |
|---|---|
| `TestBuild_MapsAllPayloadFields` | Every field from `ProductEvent.Payload` appears in `SearchDocument` with the correct value |
| `TestBuild_StampsIndexedAtAsUTC` | `IndexedAt` is non-zero and in UTC at build time |
| `TestBuild_ExcludesAuditFields` | `createdAt` and `updatedAt` are absent from `SearchDocument` — enforced by exhaustive struct literal |

### Exhaustive struct literal pattern

When asserting a `SearchDocument`, always construct the expected value as a complete struct literal with every field named. This produces a compile error if a new field is added without updating the test:

```go
// correct — compile error if SearchDocument gains a new field
expected := document.SearchDocument{
    ProductID: "...",
    Name:      "...",
    Status:    "...",
    Country:   "...",
    Version:   1,
    IndexedAt: ...,
    // deliberately no CreatedAt or UpdatedAt
}
```

Never use partial construction (`document.SearchDocument{ProductID: "..."}`) for the completeness assertion test.

### Test isolation

- `internal/document` tests must have no I/O, no network calls, and no global state.
- Do not test the Kafka consumer in unit tests. Test the builder in isolation.
