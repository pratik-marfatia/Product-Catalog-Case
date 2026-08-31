package com.example.catalog.service;

import com.example.catalog.api.ProductRequest;
import com.example.catalog.domain.Product;
import com.example.catalog.messaging.ProductEventPublisher;
import com.example.catalog.repository.ProductRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@SuppressWarnings("null") // UUIDs from caller and entities from JPA are guaranteed non-null at these call sites
public class ProductService {

    @Autowired
    private ProductRepository repository;

    @Autowired
    private ProductEventPublisher eventPublisher;

    /**
     * Upsert a product with a sourceUpdatedAt version guard.
     *
     * The native SQL upsert returns:
     *   1 — row was inserted or updated → publish event, return updated product
     *   0 — write was stale (existing record is newer) → no event, return current product
     *
     * The findById after the upsert is safe because @Modifying(clearAutomatically=true)
     * on the repository method clears the JPA L1 cache, forcing a DB read.
     */
    @Transactional
    public Product upsert(@NonNull UUID id, ProductRequest request) {
        int affected = repository.upsertProduct(
                id,
                request.name(),
                request.status().name(),
                request.country(),
                request.sourceUpdatedAt()
        );

        Product product = repository.findById(id).orElseThrow(
                () -> new IllegalStateException("Product not found after upsert: " + id)
        );

        if (affected > 0) {
            eventPublisher.publish(product);
        }

        return product;
    }

    public Optional<Product> findById(@NonNull UUID id) {
        return repository.findById(id);
    }
}
