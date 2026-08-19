package com.example.catalog.api;

import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductStatus;

import java.time.Instant;
import java.util.UUID;

/** Read representation of a product returned by GET and PUT endpoints. */
public record ProductResponse(
        UUID id,
        String name,
        ProductStatus status,
        String country,
        Long version,
        Instant sourceUpdatedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getStatus(),
                product.getCountry(),
                product.getVersion(),
                product.getSourceUpdatedAt(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
