package com.example.catalog.api;

import com.example.catalog.domain.Product;
import com.example.catalog.service.ProductService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/products")
@SuppressWarnings("null") // PathVariable UUIDs are guaranteed non-null by Spring before reaching this class
public class ProductController {

    @Autowired
    private ProductService productService;

    /**
     * Upsert a product.
     *
     * Always returns 200 with the current state of the product, regardless of
     * whether this write was accepted or discarded as stale. The caller (PIM
     * adapter) does not need to distinguish — it just needs confirmation that
     * our system has the record.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> upsertProduct(@PathVariable UUID id,
                                                         @RequestBody ProductRequest request) {
        Product product = productService.upsert(id, request);
        return ResponseEntity.ok(ProductResponse.from(product));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID id) {
        return productService.findById(id)
                .map(ProductResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
