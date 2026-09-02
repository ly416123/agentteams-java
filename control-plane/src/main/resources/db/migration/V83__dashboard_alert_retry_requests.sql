CREATE TABLE IF NOT EXISTS dashboard_alert_retry_requests (
    event_id UUID NOT NULL REFERENCES dashboard_alert_events(id) ON DELETE CASCADE,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, idempotency_key),
    CONSTRAINT dashboard_alert_retry_scope_check CHECK (tenant_id <> '' AND project_id <> ''),
    CONSTRAINT dashboard_alert_retry_key_check CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT dashboard_alert_retry_hash_check CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS dashboard_alert_retry_scope_idx
    ON dashboard_alert_retry_requests (tenant_id, project_id, created_at DESC);
