CREATE TABLE matrix_channel_bindings (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    room_id TEXT NOT NULL,
    event_types JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT matrix_channel_bindings_organization_non_blank CHECK (length(btrim(organization_id)) > 0),
    CONSTRAINT matrix_channel_bindings_tenant_non_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT matrix_channel_bindings_project_non_blank CHECK (length(btrim(project_id)) > 0),
    CONSTRAINT matrix_channel_bindings_room_non_blank CHECK (length(btrim(room_id)) > 0),
    CONSTRAINT matrix_channel_bindings_event_types_array CHECK (jsonb_typeof(event_types) = 'array'),
    CONSTRAINT matrix_channel_bindings_version_non_negative CHECK (version >= 0),
    UNIQUE (room_id)
);

CREATE INDEX matrix_channel_bindings_scope_idx
    ON matrix_channel_bindings (organization_id, tenant_id, project_id, id);
