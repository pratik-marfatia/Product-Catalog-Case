---
description: "Use when writing or modifying API endpoints, Kafka consumers, inbound DTOs, or any code that handles external input. Covers input validation, secret handling, log safety, and consumer guard-rails."
applyTo: ["product-service/src/**/*.java", "indexer/**/*.go"]
---

# Security Guard-Rails

## Input validation — all inbound DTOs

Every field on an inbound DTO (`@RequestBody` record) must have Bean Validation constraints. Never add a field without one:

```java
public record ProductRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull ProductStatus status,
    @NotBlank @Size(min = 2, max = 2) String country,
    @NotNull @PastOrPresent Instant sourceUpdatedAt
) {}
```

The controller parameter must be annotated `@Valid` — without it, constraints are silently ignored:

```java
public ResponseEntity<ProductResponse> upsertProduct(
        @PathVariable UUID id,
        @Valid @RequestBody ProductRequest request) { ... }
```

## No hardcoded credentials or secrets

Secrets (DB passwords, Kafka credentials, API keys) must always be injected from environment variables or `application.yml` placeholders. Never write a literal credential value in Java source, Go source, or any config file committed to source control:

```yaml
# correct
password: ${SPRING_DATASOURCE_PASSWORD}
```

```java
// forbidden
DataSource ds = new DataSource("catalog", "my-secret-password");
```

## Logging — no sensitive data

Never log request body contents, field values sourced from external input, or anything that could contain PII. Log only IDs, schema versions, offsets, and system state:

```java
// correct
log.info("Published ProductChanged eventId={} product={}", event.eventId(), product.getId());

// forbidden — could log PIM-sourced values
log.info("Received upsert request: {}", request);
```

```go
// correct
log.Info("indexed document", "productId", doc.ProductID, "version", doc.Version)

// forbidden
log.Info("message payload", "body", string(msg.Value))
```

## HTTP error responses — no exception details

Never return exception messages or stack traces in HTTP response bodies. Use a `@ControllerAdvice` to map validation failures to a structured `400` and unexpected errors to a generic `500`. Never pass `e.getMessage()` directly into a response:

```java
// forbidden
return ResponseEntity.badRequest().body(e.getMessage());
```

## Kafka consumers — schema version guard

Before processing any Kafka message, check `schemaVersion`. Skip messages with an unknown version rather than crashing or silently misprocessing them:

```go
const supportedSchemaVersion = 1

if event.SchemaVersion != supportedSchemaVersion {
    slog.Error("skipping unsupported schema version",
        "version", event.SchemaVersion, "offset", msg.Offset)
    return nil // skip — do not crash the consumer loop
}
```

```java
if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
    log.warn("skipping unsupported schemaVersion={} eventId={}", event.schemaVersion(), event.eventId());
    ack.acknowledge();
    return;
}
```

## Parameterised queries only

Never build SQL strings by concatenation or string interpolation. JPA and Spring Data handle parameterisation automatically. Always use `@Query` with named `@Param` bindings for native queries:

```java
// correct
@Query(value = "INSERT INTO products ... WHERE :sourceUpdatedAt > ...", nativeQuery = true)
int upsertProduct(@Param("id") UUID id, @Param("sourceUpdatedAt") Instant ts, ...);

// forbidden
String sql = "SELECT * FROM products WHERE name = '" + name + "'";
```
