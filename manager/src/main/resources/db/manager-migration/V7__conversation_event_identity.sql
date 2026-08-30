ALTER TABLE conversation_events
    ADD COLUMN IF NOT EXISTS source_event_id VARCHAR(512);

CREATE UNIQUE INDEX IF NOT EXISTS conversation_events_source_identity_idx
    ON conversation_events (session_id, source_event_id)
    WHERE source_event_id IS NOT NULL;
