CREATE TABLE task_source_evidence_calls (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    attempt_id UUID NOT NULL REFERENCES task_attempts (id),
    call_id TEXT NOT NULL,
    provider TEXT NOT NULL,
    source_count INTEGER NOT NULL,
    idempotency_key TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_source_evidence_calls_scope_not_blank
        CHECK (length(btrim(organization_id)) > 0 AND length(btrim(tenant_id)) > 0),
    CONSTRAINT task_source_evidence_calls_call_id_not_blank CHECK (length(btrim(call_id)) > 0),
    CONSTRAINT task_source_evidence_calls_provider_not_blank CHECK (length(btrim(provider)) > 0),
    CONSTRAINT task_source_evidence_calls_source_count CHECK (source_count BETWEEN 0 AND 20),
    CONSTRAINT task_source_evidence_calls_idempotency_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT task_source_evidence_calls_run_attempt_call_unique UNIQUE (run_id, attempt_id, call_id),
    CONSTRAINT task_source_evidence_calls_idempotency_unique UNIQUE (idempotency_key)
);

CREATE TABLE task_source_evidence_items (
    id UUID PRIMARY KEY,
    evidence_call_id UUID NOT NULL REFERENCES task_source_evidence_calls (id) ON DELETE CASCADE,
    source_index INTEGER NOT NULL,
    title TEXT NOT NULL,
    canonical_url TEXT NOT NULL,
    source_sha256 TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_source_evidence_items_index_non_negative CHECK (source_index >= 0),
    CONSTRAINT task_source_evidence_items_title_length CHECK (length(btrim(title)) BETWEEN 1 AND 500),
    CONSTRAINT task_source_evidence_items_url_length CHECK (length(btrim(canonical_url)) BETWEEN 1 AND 2048),
    CONSTRAINT task_source_evidence_items_hash_format CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT task_source_evidence_items_call_source_unique UNIQUE (evidence_call_id, source_sha256)
);

CREATE INDEX task_source_evidence_calls_run_idx
    ON task_source_evidence_calls (organization_id, tenant_id, task_id, run_id, captured_at, id);
CREATE INDEX task_source_evidence_items_call_idx
    ON task_source_evidence_items (evidence_call_id, source_index);
