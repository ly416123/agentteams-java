CREATE TABLE scheduled_tasks (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    organization_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT,
    cron_expression TEXT NOT NULL,
    time_zone TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    spec JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMPTZ NOT NULL,
    last_run_at TIMESTAMPTZ,
    last_task_id UUID REFERENCES tasks(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT scheduled_tasks_name_non_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT scheduled_tasks_organization_non_blank CHECK (length(btrim(organization_id)) > 0),
    CONSTRAINT scheduled_tasks_tenant_non_blank CHECK (length(btrim(tenant_id)) > 0),
    CONSTRAINT scheduled_tasks_cron_non_blank CHECK (length(btrim(cron_expression)) > 0),
    CONSTRAINT scheduled_tasks_time_zone_non_blank CHECK (length(btrim(time_zone)) > 0),
    CONSTRAINT scheduled_tasks_spec_object CHECK (jsonb_typeof(spec) = 'object'),
    CONSTRAINT scheduled_tasks_version_non_negative CHECK (version >= 0),
    UNIQUE (tenant_id, name)
);

CREATE INDEX scheduled_tasks_due_idx ON scheduled_tasks (enabled, next_run_at, id);
CREATE INDEX scheduled_tasks_scope_idx ON scheduled_tasks (organization_id, tenant_id, project_id, name);
