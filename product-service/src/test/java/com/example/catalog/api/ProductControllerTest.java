package com.example.catalog.api;

import com.example.catalog.domain.Product;
import com.example.catalog.domain.ProductStatus;
import com.example.catalog.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure unit tests for ProductController — no Spring context loaded.
 * Uses MockMvc standaloneSetup to keep tests fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductController controller;

    private MockMvc mockMvc;

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        // Configure Jackson the same way application.yml does:
        // Instant serialised as ISO-8601, not epoch millis.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // -----------------------------------------------------------------------
    // PUT /products/{id}
    // -----------------------------------------------------------------------

    @Test
    void put_returns200WithProductBody() throws Exception {
        when(productService.upsert(eq(PRODUCT_ID), any())).thenReturn(buildProduct());

        mockMvc.perform(put("/products/{id}", PRODUCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Widget Pro"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.country").value("GB"))
                .andExpect(jsonPath("$.version").value(1));
    }

    // Even a stale write returns 200 — the service resolves it and returns
    // the current DB state. The caller always gets a consistent view.
    @Test
    void put_returns200EvenWhenWriteIsStale() throws Exception {
        Product current = buildProduct(); // service returns current state regardless
        when(productService.upsert(eq(PRODUCT_ID), any())).thenReturn(current);

        mockMvc.perform(put("/products/{id}", PRODUCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // -----------------------------------------------------------------------
    // GET /products/{id}
    // -----------------------------------------------------------------------

    @Test
    void get_returns200WithProductWhenFound() throws Exception {
        when(productService.findById(PRODUCT_ID)).thenReturn(Optional.of(buildProduct()));

        mockMvc.perform(get("/products/{id}", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Widget Pro"));
    }

    @Test
    void get_returns404WhenNotFound() throws Exception {
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(productService.findById(unknown)).thenReturn(Optional.empty());

        mockMvc.perform(get("/products/{id}", unknown))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Product buildProduct() {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setName("Widget Pro");
        p.setStatus(ProductStatus.ACTIVE);
        p.setCountry("GB");
        p.setVersion(1L);
        p.setSourceUpdatedAt(Instant.parse("2026-08-19T10:00:00Z"));
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private String validRequestBody() {
        return """
                {
                  "name": "Widget Pro",
                  "status": "ACTIVE",
                  "country": "GB",
                  "sourceUpdatedAt": "2026-08-19T10:00:00Z"
                }
                """;
    }

    private String staleRequestBody() {
        return """
                {
                  "name": "Old Name",
                  "status": "INACTIVE",
                  "country": "GB",
                  "sourceUpdatedAt": "2026-08-01T00:00:00Z"
                }
                """;
    }
}
