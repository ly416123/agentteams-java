CREATE TABLE dashboard_alert_events (
    id UUID PRIMARY KEY,
    fingerprint TEXT NOT NULL UNIQUE,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    rule TEXT NOT NULL,
    severity TEXT NOT NULL,
    actual DOUBLE PRECISION NOT NULL,
    message TEXT NOT NULL,
    from_at TIMESTAMPTZ NOT NULL,
    to_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT dashboard_alert_events_scope_check CHECK (tenant_id <> '' AND project_id <> ''),
    CONSTRAINT dashboard_alert_events_range_check CHECK (from_at < to_at),
    CONSTRAINT dashboard_alert_events_actual_check CHECK (actual >= 0 AND actual <> 'NaN'::double precision),
    CONSTRAINT dashboard_alert_events_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT dashboard_alert_events_attempts_check CHECK (attempts >= 1)
);

CREATE INDEX dashboard_alert_events_due_idx
    ON dashboard_alert_events (status, next_attempt_at, updated_at);

CREATE INDEX dashboard_alert_events_scope_idx
    ON dashboard_alert_events (tenant_id, project_id, updated_at DESC);
