ALTER TABLE outbox_events
    ADD COLUMN correlation_id TEXT NOT NULL DEFAULT 'unknown',
    ADD COLUMN traceparent TEXT NOT NULL DEFAULT '',
    ADD COLUMN tracestate TEXT NOT NULL DEFAULT '';

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_correlation_id_length CHECK (char_length(correlation_id) BETWEEN 1 AND 128),
    ADD CONSTRAINT outbox_events_traceparent_length CHECK (char_length(traceparent) <= 64),
    ADD CONSTRAINT outbox_events_tracestate_length CHECK (char_length(tracestate) <= 512);
