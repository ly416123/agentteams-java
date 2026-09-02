CREATE TABLE task_recovery_checkpoints (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES task_runs(id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES task_attempts(id) ON DELETE CASCADE,
    step_key TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'COMPLETED',
    checkpoint_ref TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_recovery_checkpoints_status_check CHECK (status IN ('COMPLETED', 'INVALIDATED')),
    CONSTRAINT task_recovery_checkpoints_text_check CHECK (
        length(btrim(step_key)) > 0 AND length(btrim(idempotency_key)) > 0
        AND length(btrim(checkpoint_ref)) > 0
    ),
    CONSTRAINT task_recovery_checkpoints_version_non_negative CHECK (version >= 0),
    UNIQUE (run_id, step_key),
    UNIQUE (attempt_id, idempotency_key)
);

CREATE INDEX task_recovery_checkpoints_task_idx
    ON task_recovery_checkpoints (task_id, updated_at DESC);
