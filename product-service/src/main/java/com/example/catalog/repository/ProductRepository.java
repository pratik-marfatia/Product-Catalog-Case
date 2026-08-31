package com.example.catalog.repository;

import com.example.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * Atomic upsert with a version guard.
     *
     * On INSERT (new product): always succeeds, sets version = 1.
     * On UPDATE (existing product): only proceeds if the incoming
     *   sourceUpdatedAt is strictly newer than the stored one.
     *   If stale, the DO UPDATE ... WHERE condition is false, Postgres
     *   skips the update and returns 0 rows affected.
     *
     * Returns:
     *   1 — row was inserted or updated (publish event)
     *   0 — stale write, existing record is newer (discard silently)
     *
     * clearAutomatically = true flushes the JPA L1 cache after the
     * native query so the subsequent findById reads fresh data from the DB.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO products
                (id, name, status, country, version, source_updated_at, created_at, updated_at)
            VALUES
                (:id, :name, :status, :country, 1, :sourceUpdatedAt, NOW(), NOW())
            ON CONFLICT (id) DO UPDATE
                SET name              = EXCLUDED.name,
                    status            = EXCLUDED.status,
                    country           = EXCLUDED.country,
                    version           = products.version + 1,
                    source_updated_at = EXCLUDED.source_updated_at,
                    updated_at        = NOW()
                WHERE products.source_updated_at < EXCLUDED.source_updated_at
            """)
    int upsertProduct(@Param("id") UUID id,
                      @Param("name") String name,
                      @Param("status") String status,
                      @Param("country") String country,
                      @Param("sourceUpdatedAt") Instant sourceUpdatedAt);
}
