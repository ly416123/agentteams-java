ALTER TABLE usage_budget_events
    ADD COLUMN currency CHAR(3),
    ADD COLUMN window_end TIMESTAMPTZ,
    ADD COLUMN actual_cost NUMERIC(20, 8),
    ADD COLUMN forecast_cost NUMERIC(20, 8),
    ADD COLUMN evaluation_status TEXT,
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_error TEXT,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE usage_budget_events
    ADD CONSTRAINT usage_budget_events_attempts_check CHECK (attempts >= 1),
    ADD CONSTRAINT usage_budget_events_evaluation_status_check CHECK (evaluation_status IS NULL OR evaluation_status IN ('SOFT_LIMIT', 'HARD_LIMIT'));

CREATE INDEX usage_budget_events_due_idx
    ON usage_budget_events (status, next_attempt_at, updated_at);
