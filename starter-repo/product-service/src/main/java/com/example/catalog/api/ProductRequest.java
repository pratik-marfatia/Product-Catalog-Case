package com.example.catalog.api;

import com.example.catalog.domain.ProductStatus;

import java.time.Instant;

/**
 * Request body for PUT /products/{id}.
 *
 * sourceUpdatedAt is the PIM's own timestamp for this record.
 * It is used as the guard in the upsert: if the stored value is
 * already >= sourceUpdatedAt, the write is discarded as stale.
 */
public record ProductRequest(
        String name,
        ProductStatus status,
        String country,
        Instant sourceUpdatedAt
) {}
