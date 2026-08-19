package com.example.catalog.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Product entity.
 *
 * Descriptive fields are intentionally minimal (id, name) per the brief.
 * Every other field is control data this record needs to function correctly
 * in a multi-country, event-driven catalog system:
 *
 *   status          — drives visibility; a DISCONTINUED product must not be
 *                     surfaced by the BFF or indexed in search.
 *
 *   country         — 2 M SKUs across 8 countries; without it events and reads
 *                     cannot be scoped per market.
 *
 *   version         — optimistic concurrency counter; incremented on every
 *                     accepted upsert so consumers can detect gaps.
 *
 *   sourceUpdatedAt — the PIM's own timestamp for this record; used as the
 *                     guard condition in the upsert (see ProductRepository).
 *                     Prevents a slow bulk import from overwriting a fresher
 *                     editorial change.
 *
 *   createdAt /
 *   updatedAt       — system audit timestamps; used by the indexer to signal
 *                     new vs. updated documents, and for cache invalidation.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    /** ISO 3166-1 alpha-2 country code, e.g. "GB", "DE". */
    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Instant sourceUpdatedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
