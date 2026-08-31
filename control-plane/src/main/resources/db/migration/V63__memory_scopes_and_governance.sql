CREATE TABLE memories (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT,
    team_id TEXT,
    task_id UUID,
    subject_id TEXT,
    scope TEXT NOT NULL,
    content_ref TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
    consent_status TEXT NOT NULL DEFAULT 'CANDIDATE',
    source TEXT NOT NULL,
    retention_seconds BIGINT NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT memories_scope_check CHECK (scope IN ('USER_PRIVATE', 'ORGANIZATION_SHARED', 'PROJECT_SHARED', 'TEAM_SHARED', 'TASK')),
    CONSTRAINT memories_sensitivity_check CHECK (sensitivity IN ('NORMAL', 'SENSITIVE', 'RESTRICTED')),
    CONSTRAINT memories_consent_check CHECK (consent_status IN ('CANDIDATE', 'CONFIRMED', 'REVOKED')),
    CONSTRAINT memories_scope_owner_check CHECK (
        (scope = 'USER_PRIVATE' AND subject_id IS NOT NULL)
        OR (scope = 'ORGANIZATION_SHARED')
        OR (scope = 'PROJECT_SHARED' AND project_id IS NOT NULL)
        OR (scope = 'TEAM_SHARED' AND team_id IS NOT NULL)
        OR (scope = 'TASK' AND (project_id IS NOT NULL OR team_id IS NOT NULL))
    ),
    CONSTRAINT memories_scope_not_blank CHECK (length(btrim(organization_id)) > 0 AND length(btrim(tenant_id)) > 0),
    CONSTRAINT memories_content_ref_not_blank CHECK (length(btrim(content_ref)) > 0),
    CONSTRAINT memories_source_not_blank CHECK (length(btrim(source)) > 0),
    CONSTRAINT memories_retention_positive CHECK (retention_seconds > 0),
    CONSTRAINT memories_version_non_negative CHECK (version >= 0)
);

CREATE TABLE memory_governance_operations (
    id UUID PRIMARY KEY,
    memory_id UUID NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    reason TEXT NOT NULL,
    actor TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT memory_governance_operation_check CHECK (operation IN ('CONFIRM', 'REVOKE', 'FREEZE', 'DELETE', 'EXPORT')),
    CONSTRAINT memory_governance_text_check CHECK (length(btrim(reason)) > 0 AND length(btrim(actor)) > 0)
);

CREATE INDEX memories_tenant_scope_idx ON memories (organization_id, tenant_id, scope, sensitivity, consent_status, expires_at);
CREATE INDEX memories_subject_idx ON memories (organization_id, tenant_id, subject_id, updated_at DESC);
