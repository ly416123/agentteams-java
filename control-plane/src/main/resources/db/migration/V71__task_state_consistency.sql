CREATE TABLE task_state_consistency_issues (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    run_id UUID NOT NULL,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    issue_type TEXT NOT NULL,
    task_phase TEXT NOT NULL,
    run_status TEXT NOT NULL,
    manifest_status TEXT,
    detail TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    occurrences INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_state_consistency_scope_not_blank CHECK (
        length(btrim(organization_id)) > 0 AND length(btrim(tenant_id)) > 0),
    CONSTRAINT task_state_consistency_issue_type_not_blank CHECK (length(btrim(issue_type)) > 0),
    CONSTRAINT task_state_consistency_detail_not_blank CHECK (length(btrim(detail)) > 0),
    CONSTRAINT task_state_consistency_status_check CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT task_state_consistency_occurrences_positive CHECK (occurrences > 0),
    CONSTRAINT task_state_consistency_unique UNIQUE (task_id, run_id, issue_type)
);

CREATE INDEX task_state_consistency_open_idx
    ON task_state_consistency_issues (status, last_seen_at DESC, id);

CREATE INDEX task_state_consistency_scope_idx
    ON task_state_consistency_issues (organization_id, tenant_id, status, last_seen_at DESC);
