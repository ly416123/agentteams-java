CREATE TABLE mcp_discovery_snapshots (
    server_id UUID NOT NULL REFERENCES mcp_servers (id) ON DELETE CASCADE,
    server_revision BIGINT NOT NULL,
    instance_id VARCHAR(128) NOT NULL,
    tools_digest VARCHAR(128) NOT NULL DEFAULT '',
    healthy BOOLEAN NOT NULL,
    failure_category VARCHAR(64) NOT NULL DEFAULT '',
    observed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (server_id, server_revision, instance_id),
    CONSTRAINT mcp_discovery_snapshots_revision_check CHECK (server_revision >= 0),
    CONSTRAINT mcp_discovery_snapshots_instance_check CHECK (instance_id <> ''),
    CONSTRAINT mcp_discovery_snapshots_window_check CHECK (expires_at > observed_at),
    CONSTRAINT mcp_discovery_snapshots_category_check CHECK (
        (healthy AND failure_category IN ('', 'SUCCESS'))
        OR
        (NOT healthy AND failure_category <> '')
    )
);

CREATE INDEX mcp_discovery_snapshots_freshness_idx
    ON mcp_discovery_snapshots (server_id, server_revision, expires_at);
