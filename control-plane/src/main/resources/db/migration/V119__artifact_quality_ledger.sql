CREATE TABLE artifact_quality_events (
    id UUID PRIMARY KEY,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id UUID NOT NULL,
    task_id UUID,
    run_id UUID,
    sequence BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    artifact_name TEXT,
    file_id UUID,
    manifest_ref TEXT,
    sha256 TEXT,
    category_code TEXT,
    check_item TEXT,
    action_source TEXT,
    status TEXT NOT NULL,
    score DOUBLE PRECISION,
    score_delta DOUBLE PRECISION,
    summary TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_ref TEXT NOT NULL,
    confidence TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT artifact_quality_events_project_fk
        FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT artifact_quality_events_task_run_pair CHECK ((task_id IS NULL) = (run_id IS NULL)),
    CONSTRAINT artifact_quality_events_weak_history_only CHECK (task_id IS NOT NULL OR source_type = 'HISTORICAL'),
    CONSTRAINT artifact_quality_events_sequence_nonnegative CHECK (sequence >= 0),
    CONSTRAINT artifact_quality_events_event_type_check CHECK (event_type IN (
        'GENERATE_STARTED', 'GENERATE_SUCCEEDED', 'GENERATE_FAILED', 'QUALITY_CHECK',
        'OPTIMIZATION_ACTION', 'RECHECK', 'FINAL_STATUS'
    )),
    CONSTRAINT artifact_quality_events_source_type_check CHECK (source_type IN ('LIVE', 'HISTORICAL')),
    CONSTRAINT artifact_quality_events_confidence_check CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    CONSTRAINT artifact_quality_events_sha256_check CHECK (sha256 IS NULL OR sha256 ~ '^[A-Fa-f0-9]{64}$'),
    CONSTRAINT artifact_quality_events_lengths_check CHECK (
        length(organization_id) BETWEEN 1 AND 128 AND length(tenant_id) BETWEEN 1 AND 128
        AND length(event_type) <= 64 AND length(status) <= 64 AND length(summary) BETWEEN 1 AND 500
        AND length(source_ref) BETWEEN 1 AND 500 AND length(idempotency_key) BETWEEN 1 AND 200
        AND (artifact_name IS NULL OR length(artifact_name) <= 128)
        AND (manifest_ref IS NULL OR length(manifest_ref) <= 256)
        AND (category_code IS NULL OR length(category_code) <= 64)
        AND (check_item IS NULL OR length(check_item) <= 100)
        AND (action_source IS NULL OR length(action_source) <= 64)
    )
);

CREATE UNIQUE INDEX artifact_quality_events_idempotency_uq
    ON artifact_quality_events (organization_id, tenant_id, project_id, idempotency_key);
CREATE INDEX artifact_quality_events_project_time_idx
    ON artifact_quality_events (tenant_id, project_id, occurred_at DESC, sequence DESC, id DESC);
CREATE INDEX artifact_quality_events_task_run_idx
    ON artifact_quality_events (tenant_id, project_id, task_id, run_id, occurred_at, sequence);
CREATE INDEX artifact_quality_events_filters_idx
    ON artifact_quality_events (tenant_id, project_id, status, category_code, occurred_at DESC);
