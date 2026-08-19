CREATE TABLE IF NOT EXISTS products
(
    id                UUID         NOT NULL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    -- ACTIVE | INACTIVE | DISCONTINUED
    status            VARCHAR(20)  NOT NULL,
    -- ISO 3166-1 alpha-2, e.g. 'GB', 'DE'
    country           VARCHAR(2)   NOT NULL,
    -- Incremented on every accepted upsert; consumers use this to detect gaps.
    version           BIGINT       NOT NULL DEFAULT 1,
    -- PIM's own timestamp for this record. Used as the guard in the upsert:
    -- a write is rejected if source_updated_at <= the stored value.
    source_updated_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Support country-scoped queries (BFF reads scoped per market).
CREATE INDEX IF NOT EXISTS idx_products_country ON products (country);

-- Support filtering by status (e.g. exclude DISCONTINUED from BFF responses).
CREATE INDEX IF NOT EXISTS idx_products_status ON products (status);
