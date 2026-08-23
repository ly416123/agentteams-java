CREATE TABLE quota_reservations (
    reservation_id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    acquire_idempotency_key TEXT NOT NULL,
    estimated_tokens BIGINT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT quota_reservation_tenant_non_blank CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT quota_reservation_project_non_blank CHECK (btrim(project_id) <> ''),
    CONSTRAINT quota_reservation_key_non_blank CHECK (btrim(acquire_idempotency_key) <> ''),
    CONSTRAINT quota_reservation_tokens_non_negative CHECK (estimated_tokens >= 0),
    CONSTRAINT quota_reservation_state_valid CHECK (state IN ('PENDING', 'ACQUIRED', 'RELEASED')),
    CONSTRAINT quota_reservation_scope_key_unique UNIQUE (tenant_id, project_id, acquire_idempotency_key)
);

CREATE INDEX quota_reservations_scope_state_idx
    ON quota_reservations (tenant_id, project_id, state, updated_at);

CREATE TABLE quota_reservation_releases (
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    reservation_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    accepted BOOLEAN NOT NULL,
    protocol_error TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, project_id, idempotency_key),
    CONSTRAINT quota_release_tenant_non_blank CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT quota_release_project_non_blank CHECK (btrim(project_id) <> ''),
    CONSTRAINT quota_release_key_non_blank CHECK (btrim(idempotency_key) <> '')
);

CREATE INDEX quota_reservation_releases_reservation_idx
    ON quota_reservation_releases (reservation_id, tenant_id, project_id);
