ALTER TABLE model_call_audits
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN project_id TEXT,
    ADD COLUMN cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0;

ALTER TABLE operation_audit_events
    ADD COLUMN tenant_id TEXT,
    ADD COLUMN project_id TEXT;

CREATE INDEX model_call_audits_project_usage_idx
    ON model_call_audits (tenant_id, project_id, occurred_at DESC, id DESC);

CREATE INDEX operation_audit_events_project_scope_idx
    ON operation_audit_events (tenant_id, project_id, occurred_at DESC, id DESC);
