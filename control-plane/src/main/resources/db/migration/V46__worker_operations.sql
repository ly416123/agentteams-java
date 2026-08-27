CREATE TABLE worker_operations (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents (id),
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    requested_spec_digest TEXT,
    requested_runtime TEXT,
    requested_config_revision TEXT,
    requested_secret_generation TEXT,
    previous_stable_spec JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key TEXT NOT NULL,
    expected_agent_version BIGINT NOT NULL,
    owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    failure_category TEXT,
    correlation_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT worker_operations_type_check CHECK (type IN ('DRAIN', 'ROLLOUT', 'ROLLBACK', 'TERMINATE')),
    CONSTRAINT worker_operations_status_check CHECK
        (status IN ('PENDING', 'RUNNING', 'DRAINED', 'SUCCEEDED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT worker_operations_type_status_check CHECK (
        (type = 'DRAIN' AND status IN ('PENDING', 'RUNNING', 'DRAINED', 'FAILED'))
        OR (type = 'ROLLOUT' AND status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'ROLLED_BACK'))
        OR (type = 'ROLLBACK' AND status IN ('SUCCEEDED', 'FAILED'))
        OR (type = 'TERMINATE' AND status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
    ),
    CONSTRAINT worker_operations_key_non_blank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT worker_operations_correlation_non_blank CHECK (length(trim(correlation_id)) > 0),
    CONSTRAINT worker_operations_expected_version_non_negative CHECK (expected_agent_version >= 0),
    CONSTRAINT worker_operations_version_non_negative CHECK (version >= 0),
    CONSTRAINT worker_operations_spec_object CHECK (jsonb_typeof(previous_stable_spec) = 'object'),
    CONSTRAINT worker_operations_rollout_spec_check CHECK (
        type <> 'ROLLOUT' OR (requested_spec_digest IS NOT NULL AND requested_runtime IS NOT NULL
            AND requested_config_revision IS NOT NULL AND requested_secret_generation IS NOT NULL)
    ),
    CONSTRAINT worker_operations_non_rollout_spec_check CHECK (
        type = 'ROLLOUT' OR (requested_spec_digest IS NULL AND requested_runtime IS NULL
            AND requested_config_revision IS NULL
            AND requested_secret_generation IS NULL)
    ),
    CONSTRAINT worker_operations_active_owner_lease_check CHECK (
        status NOT IN ('PENDING', 'RUNNING') OR (owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX worker_operations_agent_idempotency_idx
    ON worker_operations (agent_id, idempotency_key);

CREATE UNIQUE INDEX worker_operations_active_idx
    ON worker_operations (agent_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX worker_operations_lease_idx
    ON worker_operations (lease_expires_at)
    WHERE status IN ('PENDING', 'RUNNING');
