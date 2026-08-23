CREATE TABLE agent_specs (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    runtime TEXT NOT NULL,
    model_provider TEXT NOT NULL,
    model_name TEXT NOT NULL,
    team_ref TEXT,
    desired_state TEXT NOT NULL,
    lifecycle_status TEXT NOT NULL,
    spec JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT agent_specs_spec_object CHECK (jsonb_typeof(spec) = 'object'),
    CONSTRAINT agent_specs_version_positive CHECK (version > 0)
);

CREATE INDEX agent_specs_state_idx ON agent_specs (desired_state, lifecycle_status, name);

CREATE TABLE agent_spec_idempotency (
    idempotency_key TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    spec_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
