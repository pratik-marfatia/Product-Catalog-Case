package com.example.catalog.service;

import com.example.catalog.api.ProductRequest;
import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductStatus;
import com.example.catalog.messaging.ProductEventPublisher;
import com.example.catalog.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository repository;

    @Mock
    private ProductEventPublisher eventPublisher;

    @InjectMocks
    private ProductService service;

    // -----------------------------------------------------------------------
    // upsert — accepted write (repository returns 1)
    // -----------------------------------------------------------------------

    @Test
    void upsert_whenAccepted_returnsProduct() {
        UUID id = UUID.randomUUID();
        Product stored = buildProduct(id, "Widget", 1L);
        stubUpsert(id, 1, stored);

        Product result = service.upsert(id, requestFor("Widget"));

        assertThat(result).isEqualTo(stored);
    }

    @Test
    void upsert_whenAccepted_publishesEvent() {
        UUID id = UUID.randomUUID();
        Product stored = buildProduct(id, "Widget", 1L);
        stubUpsert(id, 1, stored);

        service.upsert(id, requestFor("Widget"));

        verify(eventPublisher).publish(stored);
    }

    // -----------------------------------------------------------------------
    // upsert — stale write (repository returns 0 — existing record is newer)
    // -----------------------------------------------------------------------

    @Test
    void upsert_whenStale_returnsCurrentProduct() {
        UUID id = UUID.randomUUID();
        Product current = buildProduct(id, "Widget Newer", 5L);
        stubUpsert(id, 0, current);

        Product result = service.upsert(id, requestFor("Widget Older"));

        // Response always reflects DB state, never the stale payload
        assertThat(result.getName()).isEqualTo("Widget Newer");
        assertThat(result.getVersion()).isEqualTo(5L);
    }

    @Test
    void upsert_whenStale_doesNotPublishEvent() {
        UUID id = UUID.randomUUID();
        stubUpsert(id, 0, buildProduct(id, "Widget", 1L));

        service.upsert(id, requestFor("Widget"));

        verify(eventPublisher, never()).publish(any());
    }

    // -----------------------------------------------------------------------
    // findById
    // -----------------------------------------------------------------------

    @Test
    void findById_returnsProductWhenPresent() {
        UUID id = UUID.randomUUID();
        Product product = buildProduct(id, "Widget", 1L);
        when(repository.findById(id)).thenReturn(Optional.of(product));

        assertThat(service.findById(id)).contains(product);
    }

    @Test
    void findById_returnsEmptyWhenAbsent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void stubUpsert(UUID id, int affected, Product stored) {
        when(repository.upsertProduct(eq(id), any(), any(), any(), any())).thenReturn(affected);
        when(repository.findById(id)).thenReturn(Optional.of(stored));
    }

    private ProductRequest requestFor(String name) {
        return new ProductRequest(name, ProductStatus.ACTIVE, "GB", Instant.now());
    }

    private Product buildProduct(UUID id, String name, Long version) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setStatus(ProductStatus.ACTIVE);
        p.setCountry("GB");
        p.setVersion(version);
        p.setSourceUpdatedAt(Instant.now());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }
}
