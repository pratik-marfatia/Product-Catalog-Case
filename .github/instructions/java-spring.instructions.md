---
applyTo: "product-service/src/**/*.java"
---

# Java / Spring Boot — Coding Guidelines

## Layer ownership — do not cross these

| Package | Owns | Must not |
|---|---|---|
| `api/` | HTTP contract: DTOs, controller, request validation | Touch JPA entities or Kafka directly |
| `domain/` | JPA entity, enums | Contain business logic or HTTP types |
| `service/` | Business logic: upsert guard, event decision | Return JPA entities to callers outside the layer |
| `repository/` | Data access: JPQL, `ON CONFLICT` upsert | Contain business logic |
| `messaging/` | Kafka event envelope, publisher | Know about HTTP or JPA internals |

## Upsert pattern — the version guard is the core behaviour

The upsert in `ProductRepository` uses `ON CONFLICT DO UPDATE ... WHERE source_updated_at < EXCLUDED.source_updated_at`. This is intentional and must not be simplified away.

- Always annotate bulk-write queries with `@Modifying(clearAutomatically = true)` to bust the JPA L1 cache.
- The service layer decides whether to publish a Kafka event based on whether the DB row was actually updated — not on whether an exception was thrown.

## Types

- Timestamps: always `java.time.Instant`. Never `java.util.Date`, `java.time.LocalDateTime`, or `long` epoch.
- IDs: always `java.util.UUID`. Never `Long` or `Integer` for entity or event IDs.
- Enums: define in `domain/` (`ProductStatus`). Never use raw strings for status values.

## Kafka events

Every published event must have this envelope shape — do not add or remove fields without updating `schemaVersion`:

```java
// Required envelope fields on ProductEvent
UUID eventId;        // new random UUID per publish
String eventType;    // e.g. "PRODUCT_UPSERTED"
Instant occurredAt;  // Instant.now() at publish time
int schemaVersion;   // increment when payload shape changes
T payload;           // the domain object — never a JPA entity directly
```

## Spring configuration

- All Kafka topic names, bootstrap servers, and Postgres coordinates must come from `application.yml` / environment variables — never hardcode them.
- Do not add `@SpringBootTest` to unit test classes. Use `MockMvc` `standaloneSetup`.
- Do not enable Spring Security, Spring Data REST, or HATEOAS.

## Naming

- Controller methods: verb + noun (`upsertProduct`, `getProduct`).
- Repository methods: follow Spring Data naming conventions (`findById`, `upsertProduct`).
- Event classes: noun + past-tense verb (`ProductEvent`, not `ProductMessage` or `ProductDTO`).
