CREATE TABLE worker_operation_rollbacks (
    operation_id UUID PRIMARY KEY REFERENCES worker_operations(id) ON DELETE CASCADE,
    idempotency_key TEXT NOT NULL,
    expected_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT worker_operation_rollbacks_key_non_blank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT worker_operation_rollbacks_expected_version_non_negative CHECK (expected_version >= 0),
    UNIQUE (operation_id, idempotency_key)
);
