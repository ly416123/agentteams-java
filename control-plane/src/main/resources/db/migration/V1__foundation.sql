CREATE TABLE agents (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    phase TEXT NOT NULL,
    runtime TEXT NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT agents_version_non_negative CHECK (version >= 0),
    CONSTRAINT agents_capabilities_object CHECK (jsonb_typeof(capabilities) = 'object'),
    CONSTRAINT agents_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX agents_phase_idx ON agents (phase);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    phase TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    spec JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    failure_code TEXT,
    redacted_failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT tasks_version_non_negative CHECK (version >= 0),
    CONSTRAINT tasks_spec_object CHECK (jsonb_typeof(spec) = 'object')
);

CREATE INDEX tasks_phase_idx ON tasks (phase);

CREATE TABLE task_attempts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    lease_id UUID NOT NULL,
    phase TEXT NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    failure_code TEXT,
    redacted_failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_attempts_version_non_negative CHECK (version >= 0)
);

CREATE INDEX task_attempts_task_idx ON task_attempts (task_id);

CREATE TABLE task_assignments (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    attempt_id UUID NOT NULL REFERENCES task_attempts (id),
    agent_id UUID NOT NULL REFERENCES agents (id),
    phase TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_assignments_version_non_negative CHECK (version >= 0),
    CONSTRAINT task_assignments_details_object CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX task_assignments_task_idx ON task_assignments (task_id);
CREATE INDEX task_assignments_agent_idx ON task_assignments (agent_id);

CREATE TABLE agent_leases (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents (id),
    task_attempt_id UUID NOT NULL REFERENCES task_attempts (id),
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT agent_leases_version_non_negative CHECK (version >= 0)
);

ALTER TABLE task_attempts
    ADD CONSTRAINT task_attempts_lease_fk
    FOREIGN KEY (lease_id) REFERENCES agent_leases (id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX agent_leases_active_idx
    ON agent_leases (agent_id, expires_at)
    WHERE released_at IS NULL;

CREATE TABLE domain_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    aggregate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT domain_events_version_non_negative CHECK (version >= 0)
);

CREATE INDEX domain_events_aggregate_idx ON domain_events (aggregate_type, aggregate_id, aggregate_version);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    aggregate_version BIGINT NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    claim_token UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT outbox_events_version_non_negative CHECK (version >= 0),
    CONSTRAINT outbox_events_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX outbox_events_status_idx ON outbox_events (status);
CREATE INDEX outbox_events_next_attempt_idx ON outbox_events (next_attempt_at);
CREATE INDEX outbox_events_delivery_idx ON outbox_events (status, next_attempt_at);

CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    attempt_id UUID REFERENCES task_attempts (id),
    name TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT NOT NULL,
    status TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT artifacts_version_non_negative CHECK (version >= 0),
    CONSTRAINT artifacts_size_non_negative CHECK (size_bytes >= 0),
    CONSTRAINT artifacts_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX artifacts_task_idx ON artifacts (task_id);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    operation TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id UUID NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT idempotency_keys_version_non_negative CHECK (version >= 0)
);
