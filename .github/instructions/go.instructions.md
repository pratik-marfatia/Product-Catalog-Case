---
applyTo: "indexer/**/*.go"
---

# Go — Coding Guidelines

## Project layout

```
main.go                   — wiring only: create reader, start consumer loop, handle shutdown
internal/consumer/        — Kafka reading and message dispatch
internal/document/        — domain types and SearchDocument builder (pure, no I/O)
```

`internal/` packages are private to the indexer module. `main.go` must contain no business logic.

## Document builder rules

- `builder.go` maps `ProductEvent` → `SearchDocument`. It must be a pure function with no side effects, no I/O, and no global state.
- `IndexedAt` must be set to `time.Now().UTC()` at build time — not at consume time. This is intentional: it measures lag between event production and index availability.
- `createdAt` and `updatedAt` from the event payload are **deliberately excluded** from `SearchDocument`. Do not add them. They are internal audit fields and not search attributes.
- When adding a field to `SearchDocument`, update `builder_test.go` to use an exhaustive struct literal — the compile error is the intended safety net.

## Logging

- Use structured logging (`log/slog`). Every log line that represents an indexed document must include at minimum: `productId`, `country`, `version`, `indexedAt`.
- Never use `fmt.Println` for production log output.
- Log at `INFO` for successfully indexed documents, `ERROR` for consume or build failures.

## Kafka consumer

- The consumer must be idempotent: consuming the same message twice must produce the same document and the same log line.
- On fatal errors (cannot connect to Kafka on startup), exit with a non-zero code so Docker Compose can restart the container.
- On per-message errors (bad JSON, missing fields), log and skip — do not crash the consumer loop.

## Error handling

- Handle errors explicitly. Do not ignore errors with `_`.
- Wrap errors with context: `fmt.Errorf("building search document for %s: %w", productID, err)`.

## Types

- Use `time.Time` with explicit UTC for all timestamps.
- Define all domain structs in `internal/document/types.go`. Do not define types in `main.go`.
