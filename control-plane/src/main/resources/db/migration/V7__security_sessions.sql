CREATE TABLE platform_identities (
    id UUID PRIMARY KEY,
    subject TEXT NOT NULL UNIQUE,
    tenant TEXT NOT NULL,
    permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT platform_identities_permissions_array CHECK (jsonb_typeof(permissions) = 'array')
);

CREATE TABLE agent_sessions (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX agent_sessions_active_idx ON agent_sessions(agent_id, expires_at)
    WHERE revoked_at IS NULL;
