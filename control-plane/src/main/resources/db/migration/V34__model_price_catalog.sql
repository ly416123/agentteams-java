CREATE TABLE model_price_catalog (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    input_price_per_million_tokens NUMERIC(20, 8) NOT NULL,
    output_price_per_million_tokens NUMERIC(20, 8) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    lifecycle_status TEXT NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by TEXT NOT NULL,
    updated_by TEXT NOT NULL,
    CONSTRAINT model_price_catalog_tenant_non_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT model_price_catalog_project_non_blank CHECK (length(btrim(project_id)) > 0),
    CONSTRAINT model_price_catalog_provider_non_blank CHECK (length(btrim(provider)) > 0),
    CONSTRAINT model_price_catalog_model_non_blank CHECK (length(btrim(model)) > 0),
    CONSTRAINT model_price_catalog_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT model_price_catalog_input_price_non_negative
        CHECK (input_price_per_million_tokens >= 0),
    CONSTRAINT model_price_catalog_output_price_non_negative
        CHECK (output_price_per_million_tokens >= 0),
    CONSTRAINT model_price_catalog_effective_range
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT model_price_catalog_lifecycle_check
        CHECK (lifecycle_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT model_price_catalog_version_non_negative CHECK (version >= 0),
    CONSTRAINT model_price_catalog_created_by_non_blank CHECK (length(btrim(created_by)) > 0),
    CONSTRAINT model_price_catalog_updated_by_non_blank CHECK (length(btrim(updated_by)) > 0),
    CONSTRAINT model_price_catalog_effective_key
        UNIQUE (tenant_id, project_id, provider, model, currency, effective_from),
    CONSTRAINT model_price_catalog_scope_id_key UNIQUE (tenant_id, project_id, id)
);

CREATE INDEX model_price_catalog_scope_lookup_idx
    ON model_price_catalog (tenant_id, project_id, provider, model, currency,
                            lifecycle_status, effective_from DESC);

CREATE TABLE model_price_catalog_idempotency (
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    price_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, project_id, idempotency_key),
    CONSTRAINT model_price_catalog_idempotency_key_non_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT model_price_catalog_idempotency_hash_non_blank CHECK (length(btrim(request_hash)) > 0),
    CONSTRAINT model_price_catalog_idempotency_scope_non_blank
        CHECK (length(btrim(tenant_id)) > 0 AND length(btrim(project_id)) > 0),
    CONSTRAINT model_price_catalog_idempotency_price_fk
        FOREIGN KEY (tenant_id, project_id, price_id)
        REFERENCES model_price_catalog (tenant_id, project_id, id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);
