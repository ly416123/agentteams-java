CREATE TABLE mcp_connections (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    connectivity_mode TEXT NOT NULL,
    organization_id TEXT,
    tenant_id TEXT,
    endpoint_ref TEXT NOT NULL,
    credential_ref TEXT,
    allowed_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    connector_id TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    request_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT mcp_connections_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT mcp_connections_mode_check CHECK (connectivity_mode IN ('PLATFORM_PUBLIC', 'CUSTOMER_CONNECTOR', 'PRIVATE_DEPLOYMENT')),
    CONSTRAINT mcp_connections_endpoint_not_blank CHECK (length(btrim(endpoint_ref)) > 0),
    CONSTRAINT mcp_connections_scope_check CHECK (
        (connectivity_mode = 'PLATFORM_PUBLIC' AND organization_id IS NULL AND tenant_id IS NULL AND connector_id IS NULL)
        OR
        (connectivity_mode <> 'PLATFORM_PUBLIC' AND organization_id IS NOT NULL
            AND length(btrim(organization_id)) > 0 AND tenant_id IS NOT NULL AND length(btrim(tenant_id)) > 0
            AND (connectivity_mode <> 'CUSTOMER_CONNECTOR'
                OR (connector_id IS NOT NULL AND length(btrim(connector_id)) > 0)))
    ),
    CONSTRAINT mcp_connections_idempotency_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT mcp_connections_request_hash_not_blank CHECK (length(btrim(request_hash)) > 0),
    CONSTRAINT mcp_connections_version_non_negative CHECK (version >= 0)
);

CREATE TABLE mcp_connector_routes (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES mcp_connections (id) ON DELETE CASCADE,
    connector_id TEXT NOT NULL,
    route_version BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    last_heartbeat_at TIMESTAMPTZ,
    health_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT mcp_connector_routes_connector_not_blank CHECK (length(btrim(connector_id)) > 0),
    CONSTRAINT mcp_connector_routes_version_non_negative CHECK (route_version >= 0),
    CONSTRAINT mcp_connector_routes_status_check CHECK (status IN ('ACTIVE', 'STALE', 'DISABLED')),
    CONSTRAINT mcp_connector_routes_connector_unique UNIQUE (connector_id)
);

CREATE INDEX mcp_connections_tenant_idx ON mcp_connections (organization_id, tenant_id, enabled, name);
CREATE INDEX mcp_connections_mode_idx ON mcp_connections (connectivity_mode, enabled, updated_at DESC);
CREATE INDEX mcp_connector_routes_connection_idx ON mcp_connector_routes (connection_id, status, route_version DESC);
