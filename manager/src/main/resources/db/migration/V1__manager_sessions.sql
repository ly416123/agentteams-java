CREATE TABLE manager_sessions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    version BIGINT NOT NULL CHECK (version >= 0),
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, project_id, actor, idempotency_key)
);

CREATE TABLE manager_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES manager_sessions(id),
    idempotency_key VARCHAR(255) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    redacted_summary VARCHAR(512) NOT NULL,
    result_summary VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, idempotency_key)
);

CREATE TABLE manager_tool_calls (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES manager_sessions(id),
    idempotency_key VARCHAR(255) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_summary VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, idempotency_key)
);

CREATE TABLE manager_events (
    session_id UUID NOT NULL REFERENCES manager_sessions(id),
    cursor BIGINT NOT NULL CHECK (cursor > 0),
    idempotency_key VARCHAR(255),
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, cursor),
    UNIQUE (session_id, idempotency_key)
);
