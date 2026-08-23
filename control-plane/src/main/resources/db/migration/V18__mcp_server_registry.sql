CREATE TABLE mcp_servers (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    transport TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    credential_ref TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status TEXT NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT mcp_servers_transport_check CHECK (transport IN ('SSE', 'STREAMABLE_HTTP')),
    CONSTRAINT mcp_servers_health_status_check CHECK (health_status IN ('UNKNOWN', 'HEALTHY', 'UNHEALTHY')),
    CONSTRAINT mcp_servers_version_non_negative CHECK (version >= 0)
);

CREATE INDEX mcp_servers_enabled_name_idx ON mcp_servers (enabled, name);
CREATE INDEX mcp_servers_health_status_idx ON mcp_servers (health_status, last_checked_at);

CREATE TABLE mcp_server_idempotency (
    idempotency_key TEXT PRIMARY KEY,
    request_hash TEXT NOT NULL,
    server_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT mcp_server_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT mcp_server_idempotency_server_fk FOREIGN KEY (server_id)
        REFERENCES mcp_servers (id) ON DELETE SET NULL
);
