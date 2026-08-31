---
applyTo: "docker-compose.yml,**/application.yml,**/Dockerfile"
---

# Infrastructure & Configuration Guidelines

## docker-compose.yml

- All service names must match what the application expects via environment variables (`KAFKA_BOOTSTRAP_SERVERS`, `SPRING_DATASOURCE_URL`, etc.).
- Never hardcode credentials in `docker-compose.yml` for anything other than local development. The current `catalog`/`catalog`/`catalog` Postgres credentials are local-only defaults.
- `redpanda-setup` is an init container that exits 0 after enabling topic auto-creation. An `exited (0)` status in `docker compose ps` for this service is expected — do not add a `restart: always` policy to it.
- Do not add persistent volumes for Redpanda or Postgres in the local compose file — rebuild should start clean.
- Health checks must be present on `product-service` and `indexer` so dependent services wait correctly.

## application.yml (Spring Boot)

- Use Spring profiles (`application-local.yml`) for any config that differs between local and production. The base `application.yml` should work with Docker Compose environment variable overrides.
- Kafka topic names are defined once in `application.yml` and injected via `@Value`. Never hardcode the string `"product.changed"` in Java source.
- `spring.flyway.enabled=true` must remain set. Do not disable Flyway.
- Do not add `spring.jpa.hibernate.ddl-auto=create` or `update` — schema is owned by Flyway migrations.
- Logging level for `com.example.catalog` should be `INFO` in the base config. Do not set `DEBUG` globally.

## Dockerfiles

- Use multi-stage builds to keep images small.
- Java: build stage uses Maven wrapper (`./mvnw`); runtime stage uses a JRE-only base image.
- Go: build stage runs `go mod tidy && go build`; runtime stage uses `scratch` or `distroless`.
- Do not copy the `target/` directory into the image — copy only the compiled artifact.
- Do not run containers as root. Add a non-root user in the Dockerfile if the base image does not already set one.

## Kafka topics

- Topics are auto-created for this exercise. In production, create them explicitly with log compaction:
  ```bash
  rpk topic create product.changed --topic-config cleanup.policy=compact
  ```
- The partition key for `product.changed` must be `productId` to ensure ordering per product within a partition.
- Do not change the topic name `product.changed` without updating both the publisher (`ProductEventPublisher`) and the indexer consumer (`kafka.go`).
