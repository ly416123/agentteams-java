CREATE TABLE project_quota_policies (
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    max_concurrent_calls BIGINT NOT NULL DEFAULT 0,
    max_daily_calls BIGINT NOT NULL DEFAULT 0,
    max_daily_tokens BIGINT NOT NULL DEFAULT 0,
    current_concurrent_calls BIGINT NOT NULL DEFAULT 0,
    daily_calls BIGINT NOT NULL DEFAULT 0,
    daily_tokens BIGINT NOT NULL DEFAULT 0,
    usage_day DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, project_id),
    CONSTRAINT project_quota_tenant_non_blank CHECK (btrim(tenant_id) <> ''),
    CONSTRAINT project_quota_project_non_blank CHECK (btrim(project_id) <> ''),
    CONSTRAINT project_quota_max_concurrent_non_negative CHECK (max_concurrent_calls >= 0),
    CONSTRAINT project_quota_max_daily_calls_non_negative CHECK (max_daily_calls >= 0),
    CONSTRAINT project_quota_max_daily_tokens_non_negative CHECK (max_daily_tokens >= 0),
    CONSTRAINT project_quota_current_concurrent_non_negative CHECK (current_concurrent_calls >= 0),
    CONSTRAINT project_quota_daily_calls_non_negative CHECK (daily_calls >= 0),
    CONSTRAINT project_quota_daily_tokens_non_negative CHECK (daily_tokens >= 0)
);

CREATE INDEX project_quota_updated_idx
    ON project_quota_policies (updated_at, tenant_id, project_id);
