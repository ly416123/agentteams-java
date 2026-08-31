CREATE TABLE token_ledger_reservations (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT,
    task_id UUID,
    run_id UUID,
    estimated_tokens BIGINT NOT NULL,
    settled_tokens BIGINT NOT NULL DEFAULT 0,
    state TEXT NOT NULL,
    reserve_idempotency_key TEXT NOT NULL,
    reserve_request_hash CHAR(64) NOT NULL,
    settle_idempotency_key TEXT,
    settle_request_hash CHAR(64),
    release_idempotency_key TEXT,
    release_request_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT token_ledger_reservation_org_non_blank CHECK (btrim(organization_id) <> ''),
    CONSTRAINT token_ledger_reservation_tenant_non_blank CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT token_ledger_reservation_project_non_blank CHECK (project_id IS NULL OR btrim(project_id) <> ''),
    CONSTRAINT token_ledger_reservation_estimated_non_negative CHECK (estimated_tokens >= 0),
    CONSTRAINT token_ledger_reservation_settled_non_negative CHECK (settled_tokens >= 0),
    CONSTRAINT token_ledger_reservation_state_valid CHECK (state IN ('RESERVED', 'SETTLED', 'RELEASED')),
    CONSTRAINT token_ledger_reservation_reserve_key_non_blank CHECK (btrim(reserve_idempotency_key) <> ''),
    CONSTRAINT token_ledger_reservation_hashes_valid CHECK (
        reserve_request_hash ~ '^[0-9a-f]{64}$'
        AND (settle_request_hash IS NULL OR settle_request_hash ~ '^[0-9a-f]{64}$')
        AND (release_request_hash IS NULL OR release_request_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT token_ledger_reservation_terminal_keys CHECK (
        (state = 'RESERVED' AND settle_idempotency_key IS NULL AND release_idempotency_key IS NULL
            AND settle_request_hash IS NULL AND release_request_hash IS NULL)
        OR (state = 'SETTLED' AND settle_idempotency_key IS NOT NULL AND settle_request_hash IS NOT NULL
            AND release_idempotency_key IS NULL AND release_request_hash IS NULL)
        OR (state = 'RELEASED' AND release_idempotency_key IS NOT NULL AND release_request_hash IS NOT NULL
            AND settle_idempotency_key IS NULL AND settle_request_hash IS NULL)
    ),
    CONSTRAINT token_ledger_reservation_scope_key_unique
        UNIQUE (id)
);

CREATE UNIQUE INDEX token_ledger_reservations_scope_key_idx
    ON token_ledger_reservations (organization_id, tenant_id, COALESCE(project_id, ''), reserve_idempotency_key);

CREATE INDEX token_ledger_reservations_scope_state_idx
    ON token_ledger_reservations (organization_id, tenant_id, project_id, state, updated_at DESC);

CREATE TABLE token_ledger_entries (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES token_ledger_reservations(id) ON DELETE CASCADE,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT,
    task_id UUID,
    run_id UUID,
    kind TEXT NOT NULL,
    tokens BIGINT NOT NULL,
    operation_key TEXT NOT NULL,
    source TEXT NOT NULL,
    model TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT token_ledger_entry_org_non_blank CHECK (btrim(organization_id) <> ''),
    CONSTRAINT token_ledger_entry_tenant_non_blank CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT token_ledger_entry_project_non_blank CHECK (project_id IS NULL OR btrim(project_id) <> ''),
    CONSTRAINT token_ledger_entry_kind_valid CHECK (kind IN ('RESERVED', 'SETTLED', 'RELEASED')),
    CONSTRAINT token_ledger_entry_tokens_non_negative CHECK (tokens >= 0),
    CONSTRAINT token_ledger_entry_operation_non_blank CHECK (btrim(operation_key) <> ''),
    CONSTRAINT token_ledger_entry_source_non_blank CHECK (btrim(source) <> ''),
    CONSTRAINT token_ledger_entry_model_non_blank CHECK (btrim(model) <> ''),
    CONSTRAINT token_ledger_entry_reservation_kind_unique UNIQUE (reservation_id, kind),
    CONSTRAINT token_ledger_entry_scope_operation_key CHECK (length(btrim(operation_key)) > 0)
);

CREATE UNIQUE INDEX token_ledger_entries_scope_operation_idx
    ON token_ledger_entries (organization_id, tenant_id, COALESCE(project_id, ''), kind, operation_key);

CREATE INDEX token_ledger_entries_scope_time_idx
    ON token_ledger_entries (organization_id, tenant_id, project_id, occurred_at DESC);

CREATE INDEX token_ledger_entries_task_idx
    ON token_ledger_entries (tenant_id, project_id, task_id, run_id, occurred_at);
