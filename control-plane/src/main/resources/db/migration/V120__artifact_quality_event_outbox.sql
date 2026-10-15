CREATE TABLE artifact_quality_event_outbox (
    event_id UUID PRIMARY KEY,
    payload JSONB NOT NULL,
    enqueued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_type TEXT,
    CONSTRAINT artifact_quality_outbox_attempt_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT artifact_quality_outbox_error_type_length CHECK
        (last_error_type IS NULL OR length(last_error_type) <= 128)
);

CREATE INDEX artifact_quality_outbox_due_idx
    ON artifact_quality_event_outbox (next_attempt_at, enqueued_at, event_id)
    WHERE lease_until IS NULL;
