CREATE TABLE task_sandboxes (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    attempt_id UUID NOT NULL UNIQUE REFERENCES task_attempts (id),
    idempotency_key TEXT NOT NULL UNIQUE,
    provider_sandbox_id TEXT UNIQUE,
    profile TEXT NOT NULL,
    status TEXT NOT NULL,
    template TEXT NOT NULL,
    endpoint_ref TEXT,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ,
    terminated_at TIMESTAMPTZ,
    termination_reason TEXT,
    failure_code TEXT,
    redacted_failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_sandboxes_version_non_negative CHECK (version >= 0),
    CONSTRAINT task_sandboxes_profile_valid CHECK (profile IN ('NONE', 'ISOLATED', 'HARDENED')),
    CONSTRAINT task_sandboxes_status_valid CHECK (
        status IN ('REQUESTED', 'PROVISIONING', 'READY', 'RUNNING', 'STOPPING',
                   'DESTROYED', 'FAILED', 'EXPIRED', 'LOST')
    ),
    CONSTRAINT task_sandboxes_termination_consistency CHECK (
        (terminated_at IS NULL AND termination_reason IS NULL)
        OR (terminated_at IS NOT NULL AND termination_reason IS NOT NULL)
    )
);

CREATE INDEX task_sandboxes_task_idx ON task_sandboxes (task_id);
CREATE INDEX task_sandboxes_expiry_idx ON task_sandboxes (status, expires_at)
    WHERE status IN ('REQUESTED', 'PROVISIONING', 'READY', 'RUNNING');
