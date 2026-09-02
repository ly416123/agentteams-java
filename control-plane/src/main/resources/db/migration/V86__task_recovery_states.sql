CREATE TABLE task_recovery_states (
    task_id UUID PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    recovery_count INTEGER NOT NULL DEFAULT 0,
    max_recovery_attempts INTEGER NOT NULL DEFAULT 3,
    status TEXT NOT NULL DEFAULT 'READY',
    last_reason TEXT,
    next_attempt_at TIMESTAMPTZ,
    last_recovered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_recovery_states_status_check
        CHECK (status IN ('READY', 'RECOVERY_REQUIRED')),
    CONSTRAINT task_recovery_states_count_check
        CHECK (recovery_count >= 0 AND max_recovery_attempts > 0),
    CONSTRAINT task_recovery_states_version_non_negative CHECK (version >= 0),
    CONSTRAINT task_recovery_states_reason_length_check
        CHECK (last_reason IS NULL OR length(last_reason) <= 256)
);

CREATE INDEX task_recovery_states_due_idx
    ON task_recovery_states (status, next_attempt_at, updated_at);
