ALTER TABLE model_call_audits
    ADD COLUMN source_event_id UUID;

CREATE UNIQUE INDEX model_call_audits_source_event_uidx
    ON model_call_audits (source_event_id)
    WHERE source_event_id IS NOT NULL;
