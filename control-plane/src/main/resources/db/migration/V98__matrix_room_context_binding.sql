CREATE TABLE matrix_room_bindings (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    room_id TEXT NOT NULL,
    primary_context_type TEXT NOT NULL CHECK (primary_context_type IN ('PROJECT', 'TEAM', 'CONVERSATION', 'TASK')),
    primary_context_id UUID NOT NULL,
    project_id UUID,
    team_id UUID,
    conversation_id UUID,
    task_id UUID,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'MIGRATING')),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE UNIQUE INDEX matrix_room_bindings_active_room_idx
    ON matrix_room_bindings (tenant_id, room_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX matrix_room_bindings_active_context_idx
    ON matrix_room_bindings (tenant_id, primary_context_type, primary_context_id) WHERE status = 'ACTIVE';
CREATE INDEX matrix_room_bindings_context_idx
    ON matrix_room_bindings (tenant_id, primary_context_type, primary_context_id);
