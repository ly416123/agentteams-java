ALTER TABLE dashboard_alert_rules
    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT '__global__',
    ADD COLUMN IF NOT EXISTS project_id TEXT NOT NULL DEFAULT '__global__',
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE dashboard_alert_rules
    DROP CONSTRAINT IF EXISTS dashboard_alert_rules_pkey;

ALTER TABLE dashboard_alert_rules
    ADD CONSTRAINT dashboard_alert_rules_pkey PRIMARY KEY (tenant_id, project_id, rule);
