CREATE TABLE artifact_retention_project_policies (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    successful_task_retention_seconds BIGINT NOT NULL,
    failed_task_retention_seconds BIGINT NOT NULL,
    temporary_upload_retention_seconds BIGINT NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT artifact_retention_project_scope_not_blank CHECK (length(btrim(tenant_id)) > 0
        AND length(btrim(project_id)) > 0),
    CONSTRAINT artifact_retention_project_windows_non_negative CHECK (
        successful_task_retention_seconds >= 0 AND failed_task_retention_seconds >= 0
        AND temporary_upload_retention_seconds >= 0),
    CONSTRAINT artifact_retention_project_version_non_negative CHECK (version >= 0),
    CONSTRAINT artifact_retention_project_unique UNIQUE (tenant_id, project_id)
);

CREATE TABLE artifact_retention_task_overrides (
    task_id UUID PRIMARY KEY REFERENCES tasks (id) ON DELETE CASCADE,
    successful_task_retention_seconds BIGINT NOT NULL,
    failed_task_retention_seconds BIGINT NOT NULL,
    temporary_upload_retention_seconds BIGINT NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT artifact_retention_task_windows_non_negative CHECK (
        successful_task_retention_seconds >= 0 AND failed_task_retention_seconds >= 0
        AND temporary_upload_retention_seconds >= 0),
    CONSTRAINT artifact_retention_task_version_non_negative CHECK (version >= 0)
);

CREATE TABLE artifact_retention_tombstones (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES artifacts (id),
    task_id UUID NOT NULL REFERENCES tasks (id),
    storage_key_hash TEXT NOT NULL,
    policy JSONB NOT NULL,
    policy_version BIGINT NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    operator TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT artifact_retention_tombstone_artifact_unique UNIQUE (artifact_id),
    CONSTRAINT artifact_retention_tombstone_hash_format CHECK (storage_key_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT artifact_retention_tombstone_policy_object CHECK (jsonb_typeof(policy) = 'object'),
    CONSTRAINT artifact_retention_tombstone_policy_version_non_negative CHECK (policy_version >= 0),
    CONSTRAINT artifact_retention_tombstone_operator_not_blank CHECK (length(btrim(operator)) > 0),
    CONSTRAINT artifact_retention_tombstone_status_check CHECK (status IN ('PENDING', 'HELD', 'FAILED', 'DELETED')),
    CONSTRAINT artifact_retention_tombstone_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX artifact_retention_tombstones_due_idx
    ON artifact_retention_tombstones (status, next_attempt_at, id);

CREATE INDEX artifact_retention_tombstones_task_idx
    ON artifact_retention_tombstones (task_id, created_at);
