CREATE TABLE command_idempotency_keys (
    command_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    command_type TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    deduplicated BOOLEAN NOT NULL DEFAULT FALSE,
    task_id UUID,
    aggregate_version BIGINT NOT NULL,
    trace_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, command_type, idempotency_key),
    CONSTRAINT command_idempotency_status_check CHECK (status IN ('ACCEPTED', 'DUPLICATE', 'REJECTED', 'FAILED')),
    CONSTRAINT command_idempotency_version_non_negative CHECK (aggregate_version >= 0 AND version >= 0)
);

CREATE INDEX command_idempotency_command_idx ON command_idempotency_keys (command_id);
