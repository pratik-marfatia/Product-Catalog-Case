# Architecture — Product Catalog Platform

The Product Catalog Platform is an event-driven domain that owns product identity, pricing, and stock data sourced from three independent upstream systems. The core architectural choice is a **log-compacted Kafka backbone** that decouples ingestion from the two read paths (BFF and search), enables index rebuild by replay, and absorbs burst loads from PIM bulk imports and pricing campaigns. This document covers the full system design; the implemented scope is **`product-service` and `search-indexer`** only — all other services are design.

> **Navigation guide:** To understand what was built, read §2 and §4a. To evaluate design decisions, read §7 (ADRs). To understand what was deliberately omitted, see §8.

## Contents

| § | Section | What's in it |
|---|---|---|
| — | [Phase 0 Decisions](#phase-0-decisions-locked-in) | Key technology choices locked before implementation — persistence, transport, read shape, control fields |
| 1 | [System Context](#1-system-context) | What we own vs. upstream systems (PIM, Pricing SaaS, WMS) and consumers (BFF, search engine); flowchart |
| 2 | [Component Map](#2-component-map) | Internal services, Kafka topics, read layer, and indexing path; flowchart |
| 3 | [Product Model](#3-product-model) | Control fields with justification table; fields deliberately excluded |
| 4 | [Data Flows](#4-data-flows) | Sequence diagrams: §4a product path (implemented), §4b price path (design), §4c stock path (design) |
| 5 | [BFF Read Path](#5-bff-read-path) | Redis-backed composite read model; latency model; partial-document and event-ordering edge cases — **design only** |
| 6 | [Index Rebuild Strategy](#6-index-rebuild-strategy) | Log-compacted topic replay, blue/green index swap, scale check against 1-hour SLA — **design only** |
| 7 | [Architecture Decision Records](#7-architecture-decision-records) | ADR-001 Kafka · ADR-002 Log compaction · ADR-003 Composite read model · ADR-004 Outbox |
| 8 | [What Is Not Built (and Why)](#8-what-is-not-built-and-why) | Omissions table with rationale for each |

---

## Phase 0 Decisions (locked in)

These decisions were locked before writing any code; the ADRs in §7 expand on the reasoning behind each one.

| Decision | Choice | One-line rationale |
|---|---|---|
| Persistence | Postgres | Idempotent upsert with version guard via `ON CONFLICT`; already provided |
| Transport | Kafka (Redpanda) | Decoupled fan-out, replay for index rebuild, matches company event-driven default |
| BFF read shape | Composite read model | Single hop, cacheable; 3 parallel fan-out calls can't reliably hit p95 < 200 ms |
| Product control fields | See §3 | status, country, version, sourceUpdatedAt, createdAt, updatedAt |

---

## 1. System Context

What we own versus what we integrate with.

```mermaid
flowchart TB
    subgraph upstream["Upstream Systems (not ours)"]
        PIM["PIM System\nPostgres · Kafka change events\n(REST API for reconciliation only)"]
        PRICING["Pricing SaaS\nREST API (rate-limited) · webhooks"]
        WMS["Warehouse Management\nKafka topic · at-least-once · unordered"]
    end

    subgraph ours["Product Catalog Domain (what we build)"]
        PS["product-service\nJava / Spring Boot"]
        PRS["price-service\n(future — design only)"]
        SS["stock-service\n(future — design only)"]
        CRM["composite-read-model\n(future — design only)"]
        IDX["search-indexer\nGo"]
    end

    subgraph consumers["Consumers"]
        BFF["Storefront BFF\n500 rps · p95 < 200 ms"]
        SEARCH["Search Engine\n30 s visibility SLA"]
    end

    PIM -->|"pim.product.changes\nKafka topic (burst-safe)"| PS
    PRICING -->|"webhook"| PRS
    WMS -->|"Kafka topic"| SS

    PS -->|"product.changed\nKafka"| CRM
    PS -->|"product.changed\nKafka"| IDX
    PRS -->|"price.changed\nKafka"| CRM
    PRS -->|"price.changed\nKafka"| IDX
    SS -->|"stock.changed\nKafka"| CRM
    SS -->|"stock.changed\nKafka"| IDX

    CRM -->|"Redis cache"| BFF
    IDX -->|"index document"| SEARCH

    style PRS fill:#e8e8e8,stroke:#aaa,color:#888
    style SS fill:#e8e8e8,stroke:#aaa,color:#888
    style CRM fill:#e8e8e8,stroke:#aaa,color:#888
```

---

## 2. Component Map

Shows the internal structure of the Product Catalog domain — the services we own, the Kafka topics that connect them, and the two read paths (BFF and search). The three ingestion services (product, price, stock) each own one domain and publish normalised events to their respective log-compacted topics. The read layer (composite-read-model + Redis) serves the latency-sensitive BFF. The search-indexer consumes all three topics independently and builds search documents for the index. **Services shown in grey are design only and not implemented in this exercise.**

```mermaid
flowchart LR
    subgraph ingestion["Ingestion"]
        PS["product-service\n· upsert (version guard)\n· publishes ProductChanged"]
        PRS["price-service\n· webhook receiver\n· publishes PriceChanged\n(design only)"]
        SS["stock-service\n· idempotent stock merge\n· publishes StockChanged\n(design only)"]
    end

    subgraph broker["Kafka (Redpanda)"]
        T1[["product.changed\nlog-compacted"]]
        T2[["price.changed\nlog-compacted"]]
        T3[["stock.changed\nlog-compacted"]]
    end

    subgraph read["Read Layer"]
        CRM["composite-read-model\n· assembles product+price+stock\n· writes to Redis\n(design only)"]
        REDIS[("Redis\nkey: product:{id}:{country}")]
    end

    subgraph indexing["Indexing"]
        IDX["search-indexer (Go)\n· builds SearchDocument\n· logs / sends to index"]
    end

    PS --> T1
    PRS --> T2
    SS --> T3

    T1 --> CRM
    T2 --> CRM
    T3 --> CRM
    CRM --> REDIS

    T1 --> IDX
    T2 --> IDX
    T3 --> IDX

    style PRS fill:#e8e8e8,stroke:#aaa,color:#888
    style SS fill:#e8e8e8,stroke:#aaa,color:#888
    style CRM fill:#e8e8e8,stroke:#aaa,color:#888
    style REDIS fill:#e8e8e8,stroke:#aaa,color:#888
```

> **Scope of this exercise:** `product-service` and `search-indexer` are implemented.
> `price-service`, `stock-service`, and `composite-read-model` are design only.

---

## 3. Product Model

### Control fields and their justification

| Field | Type | Why it belongs |
|---|---|---|
| `id` | `UUID` | Stable identity; generated by us, not inherited from PIM (PIM ids change on re-import) |
| `name` | `String` | Minimal descriptive field per brief |
| `status` | `Enum` (`ACTIVE`, `INACTIVE`, `DISCONTINUED`) | Drives whether product is surfaced to BFF or indexed; brief explicitly lists it |
| `country` | `String` (ISO 3166-1 alpha-2) | 2M SKUs across 8 countries; without this, reads and events cannot be scoped per market |
| `version` | `Long` | Optimistic concurrency — prevents a stale bulk import (100k burst) overwriting a fresher editorial change |
| `sourceUpdatedAt` | `Instant` | Timestamp from PIM — the guard condition on upsert: reject if `sourceUpdatedAt <= existing.sourceUpdatedAt` |
| `createdAt` | `Instant` | System audit; used by indexer to signal new vs. updated documents |
| `updatedAt` | `Instant` | System audit; cache invalidation signal |

### Fields deliberately excluded

| Field | Reason |
|---|---|
| `color`, `brand`, `userType` | Descriptive attributes — brief says `id` and `name` are the only descriptive fields needed; adding more is out of scope |
| `price` | Separate domain with its own upstream, service, and event stream; embedding it here violates domain boundaries |
| `rating` | Derived/computed from reviews; not owned by PIM; separate domain |

---

## 4. Data Flows

### 4a. Product path (implemented)

```mermaid
sequenceDiagram
    participant PIM
    participant K0 as Kafka (pim.product.changes)
    participant PS as product-service
    participant DB as Postgres
    participant K as Kafka (product.changed)
    participant CRM as composite-read-model
    participant IDX as search-indexer

    PIM->>K0: publish PimProductEvent\n(editorial change or bulk import row)
    PS->>K0: consume PimProductEvent
    PS->>DB: INSERT ... ON CONFLICT DO UPDATE\nWHERE source_updated_at < EXCLUDED.source_updated_at
    DB-->>PS: upserted / skipped (stale)
    alt upserted
        PS->>K: publish ProductChanged {eventId, eventType, occurredAt,\nschemaVersion, payload}
        K-->>CRM: consume ProductChanged
        Note over CRM: Upsert product slot in composite doc\n(price + stock slots remain until their events arrive)\nSee §5 for partial-document handling
        CRM->>CRM: write to Redis (visible only if product slot present)
        K-->>IDX: consume ProductChanged
        IDX->>IDX: build SearchDocument\nlog as structured JSON
    end
```

**Why Kafka instead of REST for PIM ingestion:** PIM bulk imports arrive as bursts of 100k+ in a few minutes. Driving that over REST (either PIM calling product-service 100k times, or product-service polling PIM 100k times) exhausts connection pools, hits timeouts, and requires complex retry logic. PIM publishing to a Kafka topic costs nothing extra at burst time — Kafka absorbs the spike and product-service consumes at its own pace. The REST API is retained only for on-demand reconciliation (e.g. re-fetching a specific product after a suspected missed event).

**CDC alternative:** If PIM cannot publish to Kafka directly, Debezium CDC on PIM's PostgreSQL WAL achieves the same outcome without requiring PIM to change anything. Every INSERT/UPDATE in PIM's products table appears as a Kafka message automatically, including bulk imports.

**Idempotency note:** The `ON CONFLICT` guard on `sourceUpdatedAt` means replaying the same event twice is safe. The indexer consuming the same Kafka message twice is also safe — it builds and logs the same document.

**Outbox trade-off:** After the DB commit, the service publishes directly to Kafka. If the process crashes between commit and publish, the event is lost (at-most-once delivery to Kafka). The correct solution is the [Transactional Outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html) — write the event to an `outbox` table in the same DB transaction, relay asynchronously. This is noted as a "what I'd do next" item; direct publish is acceptable for this exercise.

---

### 4b. Price path (design only)

> **Not implemented.** Code for this path does not exist in the repository. The design below documents how it would be built.

```mermaid
sequenceDiagram
    participant SaaS as Pricing SaaS
    participant PRS as price-service
    participant K as Kafka (price.changed)
    participant CRM as composite-read-model
    participant IDX as search-indexer

    SaaS->>PRS: POST /webhooks/price (rate: ~200/s steady, ~3000/s burst)
    Note over PRS: Validate HMAC signature\nIdempotent write (deduplicate by priceId+validFrom)
    PRS->>K: publish PriceChanged
    K-->>CRM: consume → update Redis composite doc
    K-->>IDX: consume → update search document
```

**Burst handling:** At 3,000/s, Kafka absorbs the burst without back-pressure on the SaaS. The webhook receiver only writes to Kafka; downstream consumers process at their own pace.

**Rate limit concern:** If the SaaS REST API is needed for backfill (not just webhooks), the rate limit matters. Cache aggressively; only poll for products whose webhook was missed (dead-letter reconciliation).

---

### 4c. Stock path (design only)

> **Not implemented.** Code for this path does not exist in the repository. The design below documents how it would be built.

```mermaid
sequenceDiagram
    participant WMS
    participant K1 as Kafka (WMS topic)
    participant SS as stock-service
    participant K2 as Kafka (stock.changed)
    participant CRM as composite-read-model

    WMS->>K1: StockEvent (at-least-once, unordered across partitions)
    SS->>K1: consume
    Note over SS: Merge by (productId, warehouseId)\nAccept only if event.sequenceNo > stored.sequenceNo\nPartition key = productId ensures ordering per product
    SS->>K2: publish StockChanged (normalised)
    K2-->>CRM: consume → update Redis composite doc
```

**Ordering problem:** WMS publishes with no ordering guarantee across partitions. Partitioning `stock.changed` by `productId` ensures all events for a product are ordered within `stock-service`. For cross-partition replays, a `sequenceNo` (from WMS) is used as a guard — same principle as `sourceUpdatedAt` on products.

---

## 5. BFF Read Path

> **Design only — not implemented.** This section describes how the composite-read-model and Redis cache would serve the storefront BFF. No code for this path exists in the repository.

```mermaid
flowchart LR
    BFF["Storefront BFF\n500 rps"]
    REDIS[("Redis\nkey: product:{id}:{country}\nTTL: 30 s")]
    CRM["composite-read-model"]
    K1[["product.changed"]]
    K2[["price.changed"]]
    K3[["stock.changed"]]

    BFF -->|"GET /composite/{id}?country=GB"| REDIS
    REDIS -->|"cache hit ~1 ms"| BFF
    REDIS -->|"cache miss"| CRM
    CRM -->|"read from own store\n+ populate cache"| REDIS

    K1 -->|consume| CRM
    K2 -->|consume| CRM
    K3 -->|consume| CRM
```

**Latency model:**
- Cache hit: BFF → Redis → BFF ≈ 1–5 ms. p95 < 200 ms easily met.
- Cache miss: BFF → composite-read-model → its own DB → Redis → BFF ≈ 20–50 ms. Still fine.
- Cache TTL of 30 s is a reasonable default; stock changes frequently so we accept up to 30 s staleness on stock data for BFF. Price and product can have longer TTLs (5 min).

**Partial data problem — price/stock arrives before product:**

Because `product.changed`, `price.changed`, and `stock.changed` are independent Kafka topics consumed in parallel, there is no ordering guarantee across them. A `PriceChanged` event for a newly introduced productId may arrive at the composite-read-model before the corresponding `ProductChanged` event.

Handling strategy:

| Scenario | What CRM does |
|---|---|
| `ProductChanged` arrives first (normal case) | Create composite doc with product slot filled; price/stock slots empty ("unavailable") |
| `PriceChanged` or `StockChanged` arrives first | Store price/stock in a holding record keyed by productId; do **not** surface to BFF yet |
| `ProductChanged` arrives later | Merge holding record into composite doc; mark as visible to BFF |
| `ProductChanged` never arrives (orphan) | Holding record expires via TTL (e.g. 1 hour); emit a dead-letter alert |

**The key invariant:** the product slot is the anchor. A composite doc is only surfaced to the BFF once `ProductChanged` has been received for that productId. Price and stock can trail freely — missing price → BFF shows "price unavailable"; missing stock → BFF shows "out of stock". This is preferable to blocking the read until all three are present, and preferable to serving a record with a null product name.

This same invariant applies to the search indexer: the indexer only emits a `SearchDocument` for a productId once it has seen a `ProductChanged` event, even if it has already buffered price/stock data for that id.

---

## 6. Index Rebuild Strategy

> **Design only — not implemented.** Topics are auto-created in this exercise and not explicitly configured as log-compacted. Production would apply log-compaction config via `rpk topic alter` or Terraform. The rebuild script described below does not exist in this repository.

**Requirement:** Rebuild from scratch within 1 hour without taking the site down.

**Mechanism: Log-compacted Kafka topics**

All three domain topics (`product.changed`, `price.changed`, `stock.changed`) are configured as **log-compacted**. Kafka retains the latest message per key (by `productId`). This means the topic always contains the current state of every product — it is a durable, replayable snapshot.

**Rebuild procedure:**
1. Spin up a new indexer consumer group (separate from the live one).
2. Set offset to `earliest`.
3. Consume all messages — Kafka delivers only the latest per key due to compaction.
4. New indexer writes to a **new index alias** (blue/green index swap).
5. Once caught up to live offset, atomically point the search engine alias at the new index.
6. Decommission the old index.

**Scale check against the 1-hour SLA:**

| Factor | Value |
|---|---|
| Catalogue size | 2M products |
| Avg message size | ~1 KB |
| Total topic size | ~2 GB |
| Consumer throughput | 50 MB/s |
| Estimated replay time | ~40 seconds |
| Headroom (price + stock topics) | ample — well within 1 hour |

**Live indexer is unaffected** — it runs as its own consumer group and continues writing to the live index throughout.

---

## 7. Architecture Decision Records

### ADR-001 — Kafka as transport between services

**Date:** 2026-08-29
**Status:** Accepted

**Context:** Product-service needs to notify downstream consumers (indexer, future composite-read-model) when a product is created or updated. Options: synchronous HTTP callbacks, or async Kafka events.

**Decision:** Kafka (Redpanda locally).

**Consequences:**
- (+) Decoupled — consumers can be down and catch up on restart
- (+) Fan-out — add consumers without changing the producer
- (+) Log compaction enables index rebuild for free
- (+) Matches company's stated event-driven default
- (-) At-least-once delivery — consumers must be idempotent
- (-) Harder to debug than HTTP (mitigated by Redpanda Console)

---

### ADR-002 — Log-compacted topics for durable state

**Date:** 2026-08-29
**Status:** Accepted

**Context:** The search index must be rebuildable within 1 hour. Options: full DB scan via a batch job, separate snapshot API, or log-compacted Kafka topics.

**Decision:** Configure all domain topics as log-compacted.

**Consequences:**
- (+) Index rebuild is just "replay the topic" — no special rebuild endpoint needed
- (+) Compacted topic = latest state per key = equivalent to a full snapshot at zero extra cost
- (+) No TTL risk — compacted topics retain data indefinitely (by key, not by time)
- (-) Compaction is asynchronous; very recent messages may not yet be compacted (tolerable — live consumer group covers the gap)

---

### ADR-003 — Composite read model for BFF

**Date:** 2026-08-29
**Status:** Accepted

**Context:** BFF needs product + price + stock in a single response at 500 rps / p95 < 200 ms. Options: BFF fans out to three services, or a composite read model pre-assembles the document.

**Decision:** Composite read model backed by Redis.

**Consequences:**
- (+) Single network hop from BFF; p95 < 200 ms is trivially achievable
- (+) Redis cache absorbs read traffic; domain services are not on the critical path
- (-) Additional service to build (not in scope for this exercise)
- (-) Eventual consistency — composite doc lags domain events by up to seconds
- (-) "Event arrives before product exists" edge case must be handled (partial document, filled in as subsequent events arrive)

---

### ADR-004 — Direct publish vs. transactional outbox

**Date:** 2026-08-29
**Status:** Accepted — revisit before production

**Context:** After a product upsert is committed to Postgres, an event must be published to Kafka. If the service crashes between DB commit and Kafka publish, the event is silently lost.

**Decision:** For this exercise, publish directly post-commit. Document the gap.

**What production requires:** Transactional Outbox — write the event to an `outbox` table in the same DB transaction, then a relay process (Debezium CDC or a polling relay) reads the outbox and publishes to Kafka. This gives exactly-once DB write + at-least-once Kafka delivery with no silent loss.

**Consequences of direct publish:**
- (+) Simpler code; no outbox table or relay process
- (-) At-most-once Kafka delivery (event lost on crash between commit and publish)
- (-) PIM re-emitting the change event is the recovery path — acceptable if PIM is reliable; not acceptable if it is not

---

## 8. What Is Not Built (and Why)

| Item | Why omitted |
|---|---|
| `price-service` | Design only per brief. The interesting work (rate-limit handling, webhook dedup) is documented in §4b. |
| `stock-service` | Design only per brief. Ordering and idempotency are documented in §4c. |
| Composite read model | Not required; BFF read path is design only. |
| Transactional outbox | Correct production approach but adds scope; documented in ADR-004. |
| Log compaction config | Topics auto-created for this exercise; production config documented in §6. |
| Schema registry | Would version `ProductChanged` event contract. Worth adding; not worth the time here. |
| Auth / pagination / CI/CD | Explicitly out of scope per brief. |
| Real search engine (Elasticsearch / OpenSearch) | Explicitly out of scope per brief; indexer logs the document instead. |
