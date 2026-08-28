CREATE TABLE usage_budget_policies (
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    period_seconds BIGINT NOT NULL,
    soft_threshold NUMERIC(20, 8) NOT NULL,
    hard_threshold NUMERIC(20, 8) NOT NULL,
    forecast_window_seconds BIGINT NOT NULL,
    status TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT usage_budget_policies_scope_id_key UNIQUE (tenant_id, project_id, id),
    CONSTRAINT usage_budget_policies_scope_non_blank CHECK (length(btrim(tenant_id)) > 0
        AND length(btrim(project_id)) > 0),
    CONSTRAINT usage_budget_policies_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT usage_budget_policies_period_check CHECK (period_seconds >= 3600),
    CONSTRAINT usage_budget_policies_threshold_check CHECK (soft_threshold >= 0
        AND hard_threshold >= soft_threshold),
    CONSTRAINT usage_budget_policies_forecast_window_check CHECK (forecast_window_seconds > 0),
    CONSTRAINT usage_budget_policies_status_check CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT usage_budget_policies_version_check CHECK (version >= 0)
);

CREATE INDEX usage_budget_policies_scope_idx
    ON usage_budget_policies (tenant_id, project_id, status, updated_at DESC);

CREATE TABLE usage_budget_evaluations (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    actual_cost NUMERIC(20, 8),
    forecast_cost NUMERIC(20, 8),
    status TEXT NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT usage_budget_evaluations_policy_fk
        FOREIGN KEY (tenant_id, project_id, policy_id)
        REFERENCES usage_budget_policies (tenant_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT usage_budget_evaluations_window_key UNIQUE (policy_id, window_start),
    CONSTRAINT usage_budget_evaluations_window_check CHECK (window_start < window_end),
    CONSTRAINT usage_budget_evaluations_amount_check CHECK ((actual_cost IS NULL OR actual_cost >= 0)
        AND (forecast_cost IS NULL OR forecast_cost >= 0)),
    CONSTRAINT usage_budget_evaluations_status_check CHECK (status IN
        ('UNDER_BUDGET', 'SOFT_LIMIT', 'HARD_LIMIT', 'INSUFFICIENT_DATA', 'UNPRICED'))
);

CREATE INDEX usage_budget_evaluations_scope_idx
    ON usage_budget_evaluations (tenant_id, project_id, policy_id, window_start DESC);

CREATE TABLE usage_budget_events (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    fingerprint CHAR(64) NOT NULL UNIQUE,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT usage_budget_events_policy_fk
        FOREIGN KEY (tenant_id, project_id, policy_id)
        REFERENCES usage_budget_policies (tenant_id, project_id, id) ON DELETE CASCADE,
    CONSTRAINT usage_budget_events_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
