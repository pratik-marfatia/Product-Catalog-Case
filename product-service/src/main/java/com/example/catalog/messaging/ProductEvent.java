package com.example.catalog.messaging;

import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Event envelope published to the product.changed Kafka topic.
 *
 * Schema:
 *   eventId       — UUID, unique per publish; allows consumers to deduplicate.
 *   eventType     — always "product.changed" (extend to "product.deleted" later).
 *   occurredAt    — wall-clock time of the publish; not the PIM change time
 *                   (use payload.sourceUpdatedAt for the upstream change time).
 *   schemaVersion — incremented when the payload shape changes; consumers can
 *                   skip versions they don't understand rather than crashing.
 *   payload       — current state of the product at the time of publish.
 */
public record ProductEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String schemaVersion,
        Payload payload
) {

    public record Payload(
            UUID id,
            String name,
            ProductStatus status,
            String country,
            Long version,
            Instant sourceUpdatedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public static ProductEvent from(Product product) {
        return new ProductEvent(
                UUID.randomUUID().toString(),
                "product.changed",
                Instant.now(),
                "1.0",
                new Payload(
                        product.getId(),
                        product.getName(),
                        product.getStatus(),
                        product.getCountry(),
                        product.getVersion(),
                        product.getSourceUpdatedAt(),
                        product.getCreatedAt(),
                        product.getUpdatedAt()
                )
        );
    }
}
