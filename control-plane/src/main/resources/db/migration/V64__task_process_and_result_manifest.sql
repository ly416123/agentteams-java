CREATE TABLE task_runs (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_runs_scope_not_blank CHECK (length(btrim(organization_id)) > 0 AND length(btrim(tenant_id)) > 0),
    CONSTRAINT task_runs_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT task_runs_version_non_negative CHECK (version >= 0)
);

CREATE TABLE task_subtasks (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    parent_task_id UUID,
    sequence BIGINT NOT NULL,
    status TEXT NOT NULL,
    dependency_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_subtasks_sequence_non_negative CHECK (sequence >= 0),
    CONSTRAINT task_subtasks_status_check CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED')),
    CONSTRAINT task_subtasks_dependency_array CHECK (jsonb_typeof(dependency_ids) = 'array'),
    CONSTRAINT task_subtasks_run_task_unique UNIQUE (run_id, task_id)
);

CREATE TABLE task_decision_records (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    visibility TEXT NOT NULL,
    goal_summary TEXT NOT NULL,
    selected_action TEXT NOT NULL,
    evidence_summary TEXT NOT NULL DEFAULT '',
    constraints_summary TEXT NOT NULL DEFAULT '',
    confidence NUMERIC(5, 4),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_decisions_visibility_check CHECK (visibility IN ('REQUESTER', 'PROJECT_MEMBER', 'TENANT_ADMIN', 'SECURITY_AUDITOR', 'INTERNAL_ONLY')),
    CONSTRAINT task_decisions_text_not_blank CHECK (length(btrim(goal_summary)) > 0 AND length(btrim(selected_action)) > 0),
    CONSTRAINT task_decisions_confidence_check CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE TABLE task_process_events (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    sequence BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    visibility TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id TEXT NOT NULL,
    payload TEXT,
    payload_ref TEXT,
    CONSTRAINT task_process_events_scope_not_blank CHECK (length(btrim(organization_id)) > 0 AND length(btrim(tenant_id)) > 0),
    CONSTRAINT task_process_events_sequence_non_negative CHECK (sequence >= 0),
    CONSTRAINT task_process_events_visibility_check CHECK (visibility IN ('REQUESTER', 'PROJECT_MEMBER', 'TENANT_ADMIN', 'SECURITY_AUDITOR', 'INTERNAL_ONLY')),
    CONSTRAINT task_process_events_event_type_not_blank CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT task_process_events_correlation_not_blank CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT task_process_events_payload_one_of CHECK ((payload IS NULL) <> (payload_ref IS NULL)),
    CONSTRAINT task_process_events_payload_size CHECK (payload IS NULL OR octet_length(payload) <= 16384),
    CONSTRAINT task_process_events_run_sequence_unique UNIQUE (run_id, sequence)
);

CREATE TABLE task_result_manifests (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES task_runs (id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT task_result_manifests_status_check CHECK (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT task_result_manifests_version_non_negative CHECK (version >= 0),
    CONSTRAINT task_result_manifests_run_unique UNIQUE (run_id)
);

CREATE TABLE task_result_artifacts (
    id UUID PRIMARY KEY,
    manifest_id UUID NOT NULL REFERENCES task_result_manifests (id) ON DELETE CASCADE,
    artifact_name TEXT NOT NULL,
    storage_ref TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT NOT NULL,
    stage TEXT NOT NULL,
    visibility TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_result_artifacts_name_not_blank CHECK (length(btrim(artifact_name)) > 0),
    CONSTRAINT task_result_artifacts_ref_not_blank CHECK (length(btrim(storage_ref)) > 0),
    CONSTRAINT task_result_artifacts_content_type_not_blank CHECK (length(btrim(content_type)) > 0),
    CONSTRAINT task_result_artifacts_size_non_negative CHECK (size_bytes >= 0),
    CONSTRAINT task_result_artifacts_sha256_format CHECK (sha256 ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT task_result_artifacts_visibility_check CHECK (visibility IN ('REQUESTER', 'PROJECT_MEMBER', 'TENANT_ADMIN', 'SECURITY_AUDITOR', 'INTERNAL_ONLY')),
    CONSTRAINT task_result_artifacts_manifest_name_unique UNIQUE (manifest_id, artifact_name)
);

CREATE INDEX task_runs_scope_idx ON task_runs (organization_id, tenant_id, task_id, created_at DESC);
CREATE INDEX task_subtasks_parent_idx ON task_subtasks (run_id, parent_task_id, sequence);
CREATE INDEX task_decisions_task_idx ON task_decision_records (run_id, task_id, created_at);
CREATE INDEX task_process_events_scope_idx ON task_process_events (organization_id, tenant_id, task_id, sequence);
CREATE INDEX task_result_artifacts_manifest_idx ON task_result_artifacts (manifest_id, stage, visibility);
