CREATE TABLE webhook_subscriptions (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT,
    endpoint TEXT NOT NULL,
    secret_ref TEXT NOT NULL,
    event_types JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT webhook_subscriptions_organization_non_blank CHECK (length(btrim(organization_id)) > 0),
    CONSTRAINT webhook_subscriptions_tenant_non_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT webhook_subscriptions_endpoint_non_blank CHECK (length(btrim(endpoint)) > 0),
    CONSTRAINT webhook_subscriptions_secret_ref_non_blank CHECK (length(btrim(secret_ref)) > 0),
    CONSTRAINT webhook_subscriptions_event_types_array CHECK (jsonb_typeof(event_types) = 'array'),
    CONSTRAINT webhook_subscriptions_version_non_negative CHECK (version >= 0),
    UNIQUE (tenant_id, endpoint)
);

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    endpoint TEXT NOT NULL,
    secret_ref TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT webhook_deliveries_endpoint_non_blank CHECK (length(btrim(endpoint)) > 0),
    CONSTRAINT webhook_deliveries_secret_ref_non_blank CHECK (length(btrim(secret_ref)) > 0),
    CONSTRAINT webhook_deliveries_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT webhook_deliveries_status_valid CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    CONSTRAINT webhook_deliveries_attempts_non_negative CHECK (attempts >= 0),
    UNIQUE (subscription_id, event_id)
);

CREATE INDEX webhook_subscriptions_scope_idx
    ON webhook_subscriptions (organization_id, tenant_id, project_id, id);
CREATE INDEX webhook_deliveries_due_idx
    ON webhook_deliveries (status, next_attempt_at, id);
